package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.util.CardFeeSplit;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateManualBookingRequest;
import uz.minipolya.minipolya_backend.dto.request.PaymentOption;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.BookingExpiredException;
import uz.minipolya.minipolya_backend.exception.BookingPaymentConflictException;
import uz.minipolya.minipolya_backend.exception.ConflictException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.exception.SlotAlreadyBookedException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.BookingSpecifications;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
    private static final String SLOT_TAKEN = "Slot is already booked";

    private final BookingRepository bookingRepository;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final BookingPriceCalculator bookingPriceCalculator;
    private final CancellationPolicy cancellationPolicy;
    private final BookingResponseFactory bookingResponseFactory;
    private final AppProperties appProperties;
    private final PaymentService paymentService;
    private final GameService gameService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final BookingWindowValidator bookingWindowValidator;
    private final SavedCardService savedCardService;
    private final CardPricing cardPricing;

    @Transactional
    public BookingResponse createBooking(UUID userId, CreateBookingRequest request) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(request.getStadiumId(), STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not available"));

        requireNotOwnStadium(stadium, userId);

        BookingWindowValidator.requireOneHour(request.getDurationHours());
        OffsetDateTime startsAt = request.getStartsAt();
        OffsetDateTime endsAt = startsAt.plusHours(Booking.HOURS);

        bookingWindowValidator.validate(stadium, startsAt);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime latest = now.plusWeeks(appProperties.getBooking().getMaxWeeksAhead());
        if (startsAt.isAfter(latest)) {
            throw new BadRequestException(
                    "Cannot book more than " + appProperties.getBooking().getMaxWeeksAhead() + " weeks ahead");
        }

        // The owner's price N, and the one price the player sees, D: the slot's player
        // price, exactly what availability shows for it.
        long price = bookingPriceCalculator.hourPrice(stadium, startsAt);
        long cardPrice = cardPricing.playerAmount(price);
        CardFeeSplit charge = cardPricing.toOwner(amountDueNow(stadium, cardPrice, request.getPaymentOption()));

        // Resolved before the booking row exists, so an unusable card costs nothing.
        SavedCard card = savedCardService.requireOwnedCard(userId, request.getCardId());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Booking booking = Booking.builder()
                .stadium(stadium)
                .user(user)
                .source("app")
                .status("pending")
                .paymentStatus(Booking.PaymentStatus.UNPAID)
                .paidAmount(0L)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .durationHours(Booking.HOURS)
                .price(price)
                .cardPrice(cardPrice)
                .expiresAt(now.plusMinutes(appProperties.getBooking().getHoldExpiryMinutes()))
                .build();

        Booking saved;
        try {
            // Flush now so the EXCLUDE constraint fires inside this try, not at commit.
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            log.info("Booking rejected by overlap constraint: stadiumId={} {}-{}",
                    stadium.getId(), startsAt, endsAt);
            throw new SlotAlreadyBookedException(SLOT_TAKEN);
        }

        gameService.cancelGamesOnSlot(stadium.getId(), startsAt, endsAt);

        auditService.record(AuditEntry.player(userId, AuditAction.BOOKING_CREATED)
                .entity(AuditEntityType.BOOKING, saved.getId(), AuditLabels.booking(saved))
                .created("status", saved.getStatus())
                .created("startsAt", saved.getStartsAt())
                .created("durationHours", saved.getDurationHours())
                .created("price", saved.getPrice())
                .created("paymentOption", request.getPaymentOption()));
        log.info("Booking created: id={} stadiumId={} userId={} {}-{} price={} cardPrice={} charging={}",
                saved.getId(), stadium.getId(), userId, startsAt, endsAt, price, cardPrice, charge.playerAmount());

        // Confirms the booking when the money arrives; a decline leaves it
        // pending so the usual hold timeout expires it.
        paymentService.chargeForBooking(saved, card, charge);
        return bookingResponseFactory.of(saved);
    }

    /**
     * What the card is charged now, in player terms: FULL is the player price D (the sum of
     * the slots' player prices); MINIMUM is the deposit
     * {@code d = min(playerAmount(min_booking_amount), D)} — nothing else. The rest,
     * {@code D - d}, is the same whether paid later in the app or in cash.
     */
    private long amountDueNow(Stadium stadium, long playerPrice, PaymentOption option) {
        if (option == PaymentOption.FULL) {
            return playerPrice;
        }
        Long minimum = stadium.getMinBookingAmount();
        if (minimum == null) {
            throw new BadRequestException(
                    "This stadium requires full payment; it has no minimum booking amount");
        }
        return cardPricing.deposit(minimum, playerPrice);
    }

    /** A payment of a booking that is on its way or arrived: no other attempt may start. */
    private static final Set<Payment.Status> TAKEN_OR_TAKING = Set.of(
            Payment.Status.PROCESSING, Payment.Status.PAID, Payment.Status.CAPTURED, Payment.Status.COMPLETED,
            Payment.Status.HELD, Payment.Status.CAPTURE_PENDING);

    /**
     * R28: the player pays a pending booking again — another card after a decline — on the
     * same path as {@link #createBooking}: the same amount as the first attempt (the deposit
     * or the whole price), charged by {@link PaymentService#chargeForBooking}, which
     * confirms the booking on success. A decline leaves it pending with its hold unchanged
     * (never extended) and the reason on the response.
     *
     * <p>The booking row is locked for the whole call, so two taps at once run one after the
     * other and the second finds the first's payment: one charge.
     *
     * @throws BookingPaymentConflictException 409 {@code BOOKING_NOT_PENDING} (confirmed,
     *         cancelled, expired by the job), {@code BOOKING_EXPIRED} (its hold ran out),
     *         {@code PAYMENT_IN_PROGRESS} (a payment of it is processing or arrived)
     */
    @Transactional
    public BookingResponse retryPayment(UUID userId, UUID bookingId, UUID cardId) {
        Booking booking = bookingRepository.findForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        if (!"pending".equals(booking.getStatus())) {
            throw new BookingPaymentConflictException(BookingPaymentConflictException.NOT_PENDING,
                    "This booking is not awaiting payment");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (booking.getExpiresAt() == null || !now.isBefore(booking.getExpiresAt())) {
            throw new BookingPaymentConflictException(BookingPaymentConflictException.EXPIRED,
                    "The booking's hold has expired; book the slot again");
        }
        List<Payment> attempts = paymentService.paymentsOf(bookingId).stream()
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .toList();
        if (attempts.stream().anyMatch(p -> TAKEN_OR_TAKING.contains(p.getStatus()))) {
            throw new BookingPaymentConflictException(BookingPaymentConflictException.IN_PROGRESS,
                    "A payment for this booking is being processed");
        }
        SavedCard card = savedCardService.requireOwnedCard(userId, cardId);

        // The same amount as when it was created: the first attempt's (deposit or in full).
        long amount = attempts.stream()
                .filter(p -> p.getPaymentType() == Payment.PaymentType.BOOKING)
                .min(java.util.Comparator.comparing(Payment::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(Payment::getAmount)
                .orElse(booking.getCardPrice());
        Payment payment = paymentService.chargeForBooking(booking, card, cardPricing.toOwner(amount));

        auditService.record(AuditEntry.player(userId, AuditAction.BOOKING_PAYMENT_RETRIED)
                .entity(AuditEntityType.BOOKING, booking.getId(), AuditLabels.booking(booking))
                .created("amount", amount)
                .created("paymentStatus", payment.getStatus())
                .created("bookingStatus", booking.getStatus()));
        log.info("Booking payment retried: bookingId={} userId={} amount={} payment={}",
                booking.getId(), userId, amount, payment.getStatus().getValue());
        return bookingResponseFactory.of(booking);
    }

    /**
     * Pays off what is still owed on a partially paid booking from a saved
     * card. Allowed at any time while the booking is partially paid — before or
     * after the game — but never twice: the owner may have taken the same money
     * in cash, and whichever lands first wins.
     */
    @Transactional
    public BookingResponse payRemainder(UUID userId, UUID bookingId, UUID cardId) {
        // Locked, like the owner's cash: whichever settles first, the other sees it.
        Booking booking = requireBookingForUpdate(bookingId);
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        if (booking.getPaymentStatus() == Booking.PaymentStatus.PAID) {
            throw new BadRequestException("Already paid");
        }
        if (booking.getPaymentStatus() != Booking.PaymentStatus.PARTIALLY_PAID) {
            throw new BadRequestException("This booking has no part payment to complete");
        }
        if (!"confirmed".equals(booking.getStatus()) && !"completed".equals(booking.getStatus())) {
            throw new BadRequestException("Only a live booking can be paid off");
        }

        long remainder = cardPricing.remainingForPlayer(booking);
        SavedCard card = savedCardService.requireOwnedCard(userId, cardId);
        long paidBefore = booking.getPaidAmount() == null ? 0L : booking.getPaidAmount();
        Booking.PaymentStatus statusBefore = booking.getPaymentStatus();
        paymentService.chargeForBooking(booking, card, remainderCharge(booking, remainder),
                Payment.PaymentType.REMAINDER);

        // A declined card changes nothing, and then there is nothing to record.
        if (booking.getPaymentStatus() != statusBefore) {
            auditService.record(AuditEntry.player(userId, AuditAction.BOOKING_REMAINDER_PAID)
                    .entity(AuditEntityType.BOOKING, bookingId, AuditLabels.booking(booking))
                    .change("paidAmount", paidBefore, booking.getPaidAmount())
                    .change("paymentStatus", statusBefore, booking.getPaymentStatus()));
        }
        log.info("Booking remainder charged: id={} userId={} amount={} status={}",
                bookingId, userId, remainder, booking.getPaymentStatus().getValue());
        return bookingResponseFactory.of(booking);
    }

    /**
     * The remainder charge, all the owner's. On a booking priced under the player price it
     * is simply {@code D - paid}, the same amount the owner would take in cash. A booking
     * made before that rule keeps its owner's terms: its card payment records what it
     * covers of the owner's price, as such payments always did.
     */
    private CardFeeSplit remainderCharge(Booking booking, long playerAmount) {
        if (booking.getPricingModel() == Booking.PricingModel.PLAYER_PRICE) {
            return cardPricing.toOwner(playerAmount);
        }
        return new CardFeeSplit(playerAmount, playerAmount, 0L, playerAmount - booking.getRemainingAmount());
    }

    /**
     * R37: the owner took what is still owed on a booking in cash. The same money the player
     * would pay with {@link #payRemainder} — {@code remainingAmount} — recorded by
     * {@link PaymentService#recordCashPayment}; whichever lands first wins.
     *
     * <p>Applies to a live (confirmed or completed) booking that is partially paid, or a
     * manual booking still unpaid. A game booking's remainders belong to its teams
     * ({@code POST /api/games/{id}/cash}), and a pending, cancelled or expired booking has
     * nothing to complete: 400 "This booking has no part payment to complete". Nothing left:
     * 400 "Already paid". A card payment of the booking still being confirmed: 409.
     */
    @Transactional
    public BookingResponse recordCashByOwner(UUID ownerId, UUID bookingId) {
        Booking booking = requireBookingForUpdate(bookingId);
        if (!booking.getStadium().getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        if (booking.getPaymentStatus() == Booking.PaymentStatus.PAID) {
            throw new BadRequestException("Already paid");
        }
        boolean live = "confirmed".equals(booking.getStatus()) || "completed".equals(booking.getStatus());
        boolean owed = booking.getPaymentStatus() == Booking.PaymentStatus.PARTIALLY_PAID
                || (booking.getPaymentStatus() == Booking.PaymentStatus.UNPAID && "manual".equals(booking.getSource()));
        if (!live || !owed || "game".equals(booking.getSource())) {
            throw new BadRequestException("This booking has no part payment to complete");
        }
        if (paymentService.hasPaymentInFlight(bookingId)) {
            throw new ConflictException("A payment for this booking is being processed");
        }

        paymentService.recordCashPayment(bookingId, ownerId);
        log.info("Cash recorded by owner: bookingId={} ownerId={} paymentStatus={}",
                bookingId, ownerId, booking.getPaymentStatus().getValue());
        return bookingResponseFactory.forOwner(booking);
    }

    /** Owner adds a walk-in/phone booking: confirmed immediately, no hold. */
    @Transactional
    public BookingResponse createManualBooking(CreateManualBookingRequest request, UUID ownerUserId) {
        Stadium stadium = stadiumRepository.findById(request.getStadiumId())
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }

        BookingWindowValidator.requireOneHour(request.getDuration());
        OffsetDateTime startsAt = request.getStartsAt();
        OffsetDateTime endsAt = startsAt.plusHours(Booking.HOURS);

        bookingWindowValidator.validate(stadium, startsAt);

        // The owner prices a walk-in in net terms; cash carries no card fee.
        long price = bookingPriceCalculator.hourPrice(stadium, startsAt);

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
                .durationHours(Booking.HOURS)
                .price(price)
                .build();

        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            log.info("Manual booking rejected by overlap constraint: stadiumId={} {}-{}",
                    stadium.getId(), startsAt, endsAt);
            throw new SlotAlreadyBookedException(SLOT_TAKEN);
        }

        gameService.cancelGamesOnSlot(stadium.getId(), startsAt, endsAt);

        String method = request.getPaymentMethod();
        if (method == null || method.isBlank() || "cash".equalsIgnoreCase(method.trim())) {
            paymentService.recordCashPayment(saved.getId(), ownerUserId);
        }

        auditService.record(AuditEntry.owner(ownerUserId, AuditAction.MANUAL_BOOKING_CREATED)
                .entity(AuditEntityType.BOOKING, saved.getId(), AuditLabels.booking(saved))
                .created("status", saved.getStatus())
                .created("startsAt", saved.getStartsAt())
                .created("durationHours", saved.getDurationHours())
                .created("price", saved.getPrice())
                .created("clientName", saved.getClientName()));
        log.info("Manual booking created: id={} stadiumId={} ownerId={} client='{}'",
                saved.getId(), stadium.getId(), ownerUserId, request.getClientName());
        return bookingResponseFactory.forOwner(saved);
    }

    /** Pending → confirmed, called after a successful payment. Idempotent. */
    @Transactional
    public BookingResponse confirmBooking(UUID bookingId) {
        Booking booking = requireBooking(bookingId);
        if ("confirmed".equals(booking.getStatus())) {
            return bookingResponseFactory.of(booking);
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
        return bookingResponseFactory.of(saved);
    }

    @Transactional
    public BookingResponse cancelByUser(UUID userId, UUID bookingId, String reason) {
        Booking booking = requireBooking(bookingId);
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        requireCancellable(booking);

        // All or nothing: in time for the stadium's window, every card payment
        // comes back in full; past it the booking is still cancelled, but the
        // player keeps nothing.
        boolean refundable = cancellationPolicy.refundableNow(booking);
        String oldStatus = booking.getStatus();

        applyCancellation(booking, "cancelled_by_user", reason);
        long refundAmount = refundable
                ? paymentService.refundAllCardPayments(booking.getId(), reason)
                : 0L;

        safeNotify(() -> notificationService.notify(userId, Notification.Type.BOOKING_CANCELLED,
                "Бронь отменена", "Вы отменили бронь в «" + booking.getStadium().getName() + "».",
                Notification.ReferenceType.BOOKING, booking.getId()));
        // The money itself is recorded by the REFUND_CREATED entries; this says whether any was due.
        auditService.record(AuditEntry.player(userId, AuditAction.BOOKING_CANCELLED_BY_USER)
                .entity(AuditEntityType.BOOKING, booking.getId(), AuditLabels.booking(booking))
                .change("status", oldStatus, booking.getStatus())
                .reason(reason != null && !reason.isBlank() ? reason
                        : refundable ? "Cancelled in time: refunded in full"
                        : "Cancelled after the deadline: nothing refunded"));
        log.info("Booking cancelled by user: id={} userId={} inTime={} refundAmount={}",
                bookingId, userId, refundable, refundAmount);
        return withRefund(booking, refundAmount, false);
    }

    /** Owner cancellation always refunds 100%, regardless of time left. */
    @Transactional
    public BookingResponse cancelByOwner(UUID ownerId, UUID bookingId, String reason) {
        Booking booking = requireBooking(bookingId);
        if (!booking.getStadium().getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        requireCancellable(booking);
        String oldStatus = booking.getStatus();

        // The owner cancelling is never the player's fault: everything back,
        // whatever the window says. A game booking holds none of its teams'
        // money — that hangs off the game — so the game refunds them instead.
        applyCancellation(booking, "cancelled_by_owner", reason);
        Long refundedByGame = isGameBooking(booking)
                ? gameService.cancelGameForBooking(booking.getGameId(), cancelReasonOf(reason))
                : null;
        long refundAmount = refundedByGame != null
                ? refundedByGame
                : paymentService.refundAllCardPayments(booking.getId(), reason);

        if (booking.getUser() != null) {
            UUID uid = booking.getUser().getId();
            safeNotify(() -> notificationService.notify(uid, Notification.Type.BOOKING_CANCELLED,
                    "Бронь отменена владельцем",
                    "Владелец отменил вашу бронь в «" + booking.getStadium().getName()
                            + "». Возврат средств оформлен.",
                    Notification.ReferenceType.BOOKING, booking.getId()));
        }
        auditService.record(AuditEntry.owner(ownerId, AuditAction.BOOKING_CANCELLED_BY_OWNER)
                .entity(AuditEntityType.BOOKING, booking.getId(), AuditLabels.booking(booking))
                .change("status", oldStatus, booking.getStatus())
                .reason(reason));
        log.info("Booking cancelled by owner: id={} ownerId={} refundAmount={}", bookingId, ownerId, refundAmount);
        return withRefund(booking, refundAmount, true);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getMyBookings(UUID userId, int page, int size) {
        return getMyBookings(userId, null, page, size);
    }

    /**
     * R29: the player's bookings, all of them (latest start first, as before), or split:
     * {@code upcoming} — starting now or later, soonest first; {@code past} — started
     * already, newest first. Anything else is 400 "Invalid when".
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getMyBookings(UUID userId, String when, int page, int size) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (when == null || when.isBlank()) {
            Pageable pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt"));
            return PageResponse.from(bookingRepository.findByUserId(userId, pageable), bookingResponseFactory::of);
        }
        return switch (when) {
            case "upcoming" -> PageResponse.from(bookingRepository.findByUserIdAndStartsAtGreaterThanEqual(userId, now,
                    Pagination.of(page, size, Sort.by(Sort.Direction.ASC, "startsAt"))), bookingResponseFactory::of);
            case "past" -> PageResponse.from(bookingRepository.findByUserIdAndStartsAtBefore(userId, now,
                    Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt"))), bookingResponseFactory::of);
            default -> throw new BadRequestException("Invalid when");
        };
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingForUser(UUID userId, UUID bookingId) {
        Booking booking = requireBooking(bookingId);
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        return bookingResponseFactory.of(booking);
    }

    /** Owner's unified schedule (app + manual bookings), optionally within a date range. */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getStadiumBookings(UUID ownerId, UUID stadiumId,
                                                            LocalDate from, LocalDate to, int page, int size) {
        return getStadiumBookings(ownerId, stadiumId, from, to, false, page, size);
    }

    /**
     * As above; with {@code unpaid}, only live bookings (pending, confirmed, completed) with
     * {@code remainingAmount > 0}, soonest first — filtered in the query
     * ({@link BookingSpecifications#ownerUnpaid}), so the pages and totals are exact (R38).
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getStadiumBookings(UUID ownerId, UUID stadiumId, LocalDate from,
                                                            LocalDate to, boolean unpaid, int page, int size) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        Pageable pageable = Pagination.of(page, size, Sort.by(Sort.Direction.ASC, "startsAt"));
        if (unpaid) {
            pageable = Pagination.of(page, size,
                    Sort.by(Sort.Direction.ASC, "startsAt").and(Sort.by(Sort.Direction.ASC, "id")));
            OffsetDateTime fromUtc = from == null ? null : from.atStartOfDay(TASHKENT).toOffsetDateTime();
            OffsetDateTime toUtc = to == null ? null : to.plusDays(1).atStartOfDay(TASHKENT).toOffsetDateTime();
            return PageResponse.from(
                    bookingRepository.findAll(BookingSpecifications.ownerUnpaid(stadiumId, fromUtc, toUtc), pageable),
                    bookingResponseFactory::forOwner);
        }
        if (from != null && to != null) {
            OffsetDateTime fromUtc = from.atStartOfDay(TASHKENT).toOffsetDateTime();
            OffsetDateTime toUtc = to.plusDays(1).atStartOfDay(TASHKENT).toOffsetDateTime();
            return PageResponse.from(
                    bookingRepository.findByStadiumIdAndStartsAtBetween(stadiumId, fromUtc, toUtc, pageable),
                    bookingResponseFactory::forOwner);
        }
        return PageResponse.from(bookingRepository.findByStadiumId(stadiumId, pageable),
                bookingResponseFactory::forOwner);
    }

    // ---- Helpers ----

    /**
     * An owner must not take their own pitch through the player flow — they add a
     * manual booking instead, which is confirmed immediately and needs no hold.
     */
    private void requireNotOwnStadium(Stadium stadium, UUID userId) {
        if (stadium.getOwner().getId().equals(userId)) {
            throw new ForbiddenException(
                    "You cannot book your own stadium; add a manual booking instead");
        }
    }

    /** A booking created by a game: its money belongs to the game, not to it. */
    private boolean isGameBooking(Booking booking) {
        return "game".equals(booking.getSource()) && booking.getGameId() != null;
    }

    private String cancelReasonOf(String reason) {
        return reason == null || reason.isBlank() ? "The owner cancelled the booking" : reason;
    }

    private void applyCancellation(Booking booking, String status, String reason) {
        booking.setStatus(status);
        booking.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC));
        booking.setCancellationReason(reason);
        bookingRepository.save(booking);
    }

    private BookingResponse withRefund(Booking booking, long refundAmount, boolean ownerView) {
        BookingResponse response = ownerView ? bookingResponseFactory.forOwner(booking) : bookingResponseFactory.of(booking);
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

    private Booking requireBookingForUpdate(UUID bookingId) {
        return bookingRepository.findForUpdate(bookingId)
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
