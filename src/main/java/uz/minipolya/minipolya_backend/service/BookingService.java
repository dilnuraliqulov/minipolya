package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingRequest;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.BookingExpiredException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.exception.SlotAlreadyBookedException;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;
import uz.minipolya.minipolya_backend.util.RefundCalculator;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Booking lifecycle and the double-booking safety net. The {@code
 * bookings_no_overlap} EXCLUDE constraint is the ultimate guard; this service
 * also cancels any waiting games whose slot a new booking takes.
 *
 * <p>Cancellation refunds are issued as payment rows via {@link PaymentService}.
 * When a booking takes a slot, {@link GameService} cancels any waiting games on
 * that slot and refunds their participants.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    /** Pricing rules are keyed by local weekday/time; bookings are stored in UTC. */
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final String STATUS_APPROVED = "approved";

    private final BookingRepository bookingRepository;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final PriceCalculator priceCalculator;
    private final RefundCalculator refundCalculator;
    private final BookingMapper bookingMapper;
    private final AppProperties appProperties;
    private final PaymentService paymentService;
    private final GameService gameService;

    @Transactional
    public BookingResponse createBooking(Long userId, CreateBookingRequest request) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(request.getStadiumId(), STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not available"));

        int duration = request.getDurationHours();
        OffsetDateTime startsAt = request.getStartsAt();
        OffsetDateTime endsAt = startsAt.plusHours(duration);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime latest = now.plusWeeks(appProperties.getBooking().getMaxWeeksAhead());
        if (startsAt.isAfter(latest)) {
            throw new BadRequestException(
                    "Cannot book more than " + appProperties.getBooking().getMaxWeeksAhead() + " weeks ahead");
        }

        int price = calculatePrice(stadium, startsAt, duration);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Booking booking = Booking.builder()
                .stadium(stadium)
                .user(user)
                .source("app")
                .status("pending")
                .startsAt(startsAt)
                .endsAt(endsAt)
                .durationHours(duration)
                .price(price)
                .expiresAt(now.plusMinutes(appProperties.getBooking().getHoldExpiryMinutes()))
                .build();

        Booking saved;
        try {
            // Flush now so the EXCLUDE constraint fires inside this try, not at commit.
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            log.info("Booking rejected by overlap constraint: stadiumId={} {}-{}",
                    stadium.getId(), startsAt, endsAt);
            throw new SlotAlreadyBookedException("Slot is already booked");
        }

        gameService.cancelGamesOnSlot(stadium.getId(), startsAt, endsAt);

        log.info("Booking created: id={} stadiumId={} userId={} {}-{} price={}",
                saved.getId(), stadium.getId(), userId, startsAt, endsAt, price);
        return bookingMapper.toResponse(saved);
    }

    /** Pending → confirmed, called after a successful payment. Idempotent. */
    @Transactional
    public BookingResponse confirmBooking(Long bookingId) {
        Booking booking = requireBooking(bookingId);
        if ("confirmed".equals(booking.getStatus())) {
            return bookingMapper.toResponse(booking);
        }
        if ("expired".equals(booking.getStatus())) {
            throw new BookingExpiredException("Booking has expired");
        }
        if (!"pending".equals(booking.getStatus())) {
            throw new BadRequestException("Only a pending booking can be confirmed");
        }
        booking.setStatus("confirmed");
        booking.setExpiresAt(null);
        Booking saved = bookingRepository.save(booking);
        log.info("Booking confirmed: id={}", bookingId);
        return bookingMapper.toResponse(saved);
    }

    @Transactional
    public BookingResponse cancelByUser(Long userId, Long bookingId, String reason) {
        Booking booking = requireBooking(bookingId);
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        requireCancellable(booking);

        int refundPercent = "pending".equals(booking.getStatus())
                ? 0 // nothing was paid yet
                : refundPercentFor(booking);
        int refundAmount = refundCalculator.refundAmount(booking.getPrice(), refundPercent);

        applyCancellation(booking, "cancelled_by_user", reason);
        if (refundAmount > 0) {
            paymentService.refundBookingPayment(booking.getId(), refundAmount, reason);
        }
        log.info("Booking cancelled by user: id={} userId={} refundPercent={} refundAmount={}",
                bookingId, userId, refundPercent, refundAmount);
        return withRefund(booking, refundAmount);
    }

    /** Owner cancellation always refunds 100%, regardless of time left. */
    @Transactional
    public BookingResponse cancelByOwner(Long ownerId, Long bookingId, String reason) {
        Booking booking = requireBooking(bookingId);
        if (!booking.getStadium().getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        requireCancellable(booking);

        int refundAmount = booking.getPrice();
        applyCancellation(booking, "cancelled_by_owner", reason);
        if (refundAmount > 0) {
            paymentService.refundBookingPayment(booking.getId(), refundAmount, reason);
        }
        log.info("Booking cancelled by owner: id={} ownerId={} refundAmount={}", bookingId, ownerId, refundAmount);
        return withRefund(booking, refundAmount);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getMyBookings(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt"));
        return PageResponse.from(bookingRepository.findByUserId(userId, pageable), bookingMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingForUser(Long userId, Long bookingId) {
        Booking booking = requireBooking(bookingId);
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        return bookingMapper.toResponse(booking);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getStadiumBookings(Long ownerId, Long stadiumId, int page, int size) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt"));
        return PageResponse.from(bookingRepository.findByStadiumId(stadiumId, pageable), bookingMapper::toResponse);
    }

    // ---- Helpers ----

    private int calculatePrice(Stadium stadium, OffsetDateTime startsAt, int duration) {
        ZonedDateTime local = startsAt.atZoneSameInstant(TASHKENT);
        return priceCalculator.calculateTotalPrice(
                pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadium.getId()),
                local.toLocalDate(),
                local.toLocalTime(),
                duration,
                stadium.getPricePerHour());
    }

    private int refundPercentFor(Booking booking) {
        long hoursLeft = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), booking.getStartsAt()).toHours();
        Stadium stadium = booking.getStadium();
        return refundCalculator.refundPercent(
                hoursLeft,
                stadium.getFullRefundHours(),
                stadium.getPartialRefundHours(),
                stadium.getPartialRefundPercent());
    }

    private void applyCancellation(Booking booking, String status, String reason) {
        booking.setStatus(status);
        booking.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC));
        booking.setCancellationReason(reason);
        bookingRepository.save(booking);
    }

    private BookingResponse withRefund(Booking booking, int refundAmount) {
        BookingResponse response = bookingMapper.toResponse(booking);
        response.setRefundAmount(refundAmount);
        return response;
    }

    private void requireCancellable(Booking booking) {
        String status = booking.getStatus();
        if (!"pending".equals(status) && !"confirmed".equals(status)) {
            throw new BadRequestException("Booking cannot be cancelled in status " + status);
        }
    }

    private Booking requireBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }
}
