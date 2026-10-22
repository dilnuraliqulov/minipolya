package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateManualBookingRequest;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.BookingExpiredException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.exception.SlotAlreadyBookedException;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.RefundCalculator;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

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
    private final BookingPriceCalculator bookingPriceCalculator;
    private final RefundCalculator refundCalculator;
    private final BookingMapper bookingMapper;
    private final AppProperties appProperties;
    private final PaymentService paymentService;
    private final GameService gameService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final StadiumClosureService closureService;

    @Transactional
    public BookingResponse createBooking(UUID userId, CreateBookingRequest request) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(request.getStadiumId(), STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not available"));

        int duration = request.getDurationHours();
        OffsetDateTime startsAt = request.getStartsAt();
        OffsetDateTime endsAt = startsAt.plusHours(duration);

        validateBookingWindow(stadium, startsAt, duration);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime latest = now.plusWeeks(appProperties.getBooking().getMaxWeeksAhead());
        if (startsAt.isAfter(latest)) {
            throw new BadRequestException(
                    "Cannot book more than " + appProperties.getBooking().getMaxWeeksAhead() + " weeks ahead");
        }

        int price = bookingPriceCalculator.totalPrice(stadium, startsAt, duration);

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
            throw new SlotAlreadyBookedException(overlapMessage(duration));
        }

        gameService.cancelGamesOnSlot(stadium.getId(), startsAt, endsAt);

        auditService.log(userId, "BOOKING_CREATED", "booking", saved.getId());
        log.info("Booking created: id={} stadiumId={} userId={} {}-{} price={}",
                saved.getId(), stadium.getId(), userId, startsAt, endsAt, price);
        return bookingMapper.toResponse(saved);
    }

    /** Owner adds a walk-in/phone booking: confirmed immediately, no hold. */
    @Transactional
    public BookingResponse createManualBooking(CreateManualBookingRequest request, UUID ownerUserId) {
        Stadium stadium = stadiumRepository.findById(request.getStadiumId())
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }

        int duration = request.getDuration();
        OffsetDateTime startsAt = request.getStartsAt();
        OffsetDateTime endsAt = startsAt.plusHours(duration);

        validateBookingWindow(stadium, startsAt, duration);

        int price = bookingPriceCalculator.totalPrice(stadium, startsAt, duration);

        Booking booking = Booking.builder()
                .stadium(stadium)
                .user(null) // walk-in client is not a registered user
                .source("manual")
                .status("confirmed")
                .isWalkin(true)
                .clientName(request.getClientName())
                .clientPhone(request.getClientPhone())
                .startsAt(startsAt)
                .endsAt(endsAt)
                .durationHours(duration)
                .price(price)
                .build();

        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            log.info("Manual booking rejected by overlap constraint: stadiumId={} {}-{}",
                    stadium.getId(), startsAt, endsAt);
            throw new SlotAlreadyBookedException(overlapMessage(duration));
        }

        gameService.cancelGamesOnSlot(stadium.getId(), startsAt, endsAt);

        String method = request.getPaymentMethod();
        if (method == null || method.isBlank() || "cash".equalsIgnoreCase(method.trim())) {
            paymentService.recordCashPayment(saved.getId(), ownerUserId);
        }

        auditService.log(ownerUserId, "MANUAL_BOOKING_CREATED", "booking", saved.getId());
        log.info("Manual booking created: id={} stadiumId={} ownerId={} client='{}'",
                saved.getId(), stadium.getId(), ownerUserId, request.getClientName());
        return bookingMapper.toResponse(saved);
    }

    /** Pending → confirmed, called after a successful payment. Idempotent. */
    @Transactional
    public BookingResponse confirmBooking(UUID bookingId) {
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
    public BookingResponse cancelByUser(UUID userId, UUID bookingId, String reason) {
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
        safeNotify(() -> notificationService.notify(userId, Notification.Type.BOOKING_CANCELLED,
                "Бронь отменена", "Вы отменили бронь в «" + booking.getStadium().getName() + "».",
                Notification.ReferenceType.BOOKING, booking.getId()));
        auditService.log(userId, "BOOKING_CANCELLED_BY_USER", "booking", booking.getId(),
                "{\"refundAmount\":" + refundAmount + "}");
        log.info("Booking cancelled by user: id={} userId={} refundPercent={} refundAmount={}",
                bookingId, userId, refundPercent, refundAmount);
        return withRefund(booking, refundAmount);
    }

    /** Owner cancellation always refunds 100%, regardless of time left. */
    @Transactional
    public BookingResponse cancelByOwner(UUID ownerId, UUID bookingId, String reason) {
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
        if (booking.getUser() != null) {
            UUID uid = booking.getUser().getId();
            safeNotify(() -> notificationService.notify(uid, Notification.Type.BOOKING_CANCELLED,
                    "Бронь отменена владельцем",
                    "Владелец отменил вашу бронь в «" + booking.getStadium().getName()
                            + "». Возврат средств оформлен.",
                    Notification.ReferenceType.BOOKING, booking.getId()));
        }
        auditService.log(ownerId, "BOOKING_CANCELLED_BY_OWNER", "booking", booking.getId());
        log.info("Booking cancelled by owner: id={} ownerId={} refundAmount={}", bookingId, ownerId, refundAmount);
        return withRefund(booking, refundAmount);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getMyBookings(UUID userId, int page, int size) {
        Pageable pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt"));
        return PageResponse.from(bookingRepository.findByUserId(userId, pageable), bookingMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingForUser(UUID userId, UUID bookingId) {
        Booking booking = requireBooking(bookingId);
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        return bookingMapper.toResponse(booking);
    }

    /** Owner's unified schedule (app + manual bookings), optionally within a date range. */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getStadiumBookings(UUID ownerId, UUID stadiumId,
                                                            LocalDate from, LocalDate to, int page, int size) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        Pageable pageable = Pagination.of(page, size, Sort.by(Sort.Direction.ASC, "startsAt"));
        if (from != null && to != null) {
            OffsetDateTime fromUtc = from.atStartOfDay(TASHKENT).toOffsetDateTime();
            OffsetDateTime toUtc = to.plusDays(1).atStartOfDay(TASHKENT).toOffsetDateTime();
            return PageResponse.from(
                    bookingRepository.findByStadiumIdAndStartsAtBetween(stadiumId, fromUtc, toUtc, pageable),
                    bookingMapper::toResponse);
        }
        return PageResponse.from(bookingRepository.findByStadiumId(stadiumId, pageable), bookingMapper::toResponse);
    }

    // ---- Helpers ----

    /**
     * Shared gate for app and manual bookings. A booking is a run of consecutive
     * whole 1-hour slots: it must start on the hour, be at least 1 hour, sit
     * entirely within the stadium's Tashkent open–close window, and not touch any
     * closure. Overlap with other bookings is caught separately by the DB EXCLUDE
     * constraint. The open–close check mirrors {@link AvailabilityService}: that
     * endpoint treats close_time as a same-day bound and produces no slots when
     * close_time &lt;= open_time, so such a window is unbookable here too.
     */
    private void validateBookingWindow(Stadium stadium, OffsetDateTime startsAt, int duration) {
        if (duration < 1) {
            throw new BadRequestException("Duration must be at least 1 hour");
        }

        ZonedDateTime start = startsAt.atZoneSameInstant(TASHKENT);
        LocalTime startLocal = start.toLocalTime();
        if (startLocal.getMinute() != 0 || startLocal.getSecond() != 0 || startLocal.getNano() != 0) {
            throw new BadRequestException("Booking must start on the hour");
        }

        LocalTime open = stadium.getOpenTime();
        LocalTime close = stadium.getCloseTime();
        if (!open.isBefore(close)) {
            throw new BadRequestException("Stadium has no bookable hours");
        }
        if (startLocal.isBefore(open)) {
            throw new BadRequestException("Booking must start at or after " + open);
        }
        ZonedDateTime end = start.plusHours(duration);
        boolean endsSameDay = end.toLocalDate().equals(start.toLocalDate());
        if (!endsSameDay || end.toLocalTime().isAfter(close)) {
            throw new BadRequestException("Booking must end by " + close);
        }

        for (int hour = 0; hour < duration; hour++) {
            ZonedDateTime slot = start.plusHours(hour);
            if (closureService.isClosedAt(stadium.getId(), slot.toLocalDate(), slot.toLocalTime())) {
                throw new BadRequestException("Stadium is closed on " + slot.toLocalDate());
            }
        }
    }

    private String overlapMessage(int duration) {
        return duration > 1
                ? "One or more of the selected hours is already booked"
                : "Slot is already booked";
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

    private Booking requireBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    /** Notifications are best-effort — a failure must never break the booking flow. */
    private void safeNotify(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("Notification failed: {}", ex.toString());
        }
    }
}
