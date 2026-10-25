package uz.minipolya.minipolya_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.ChargeCommand;
import uz.minipolya.minipolya_backend.provider.LegacyCheckoutProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.ProviderTransaction;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Payment lifecycle: initiate a checkout (booking or game share), handle the
 * provider webhook, record cash, and issue refunds. Completing a payment
 * confirms its booking, or notifies {@link GameService} for a game share.
 * Refunds are always new rows with negative amounts — originals are never
 * mutated.
 *
 * <p>{@link GameService} is injected lazily to break the
 * GameService ↔ PaymentService cycle.
 */
@Slf4j
@Service
public class PaymentService {

    private static final Set<String> SUCCESS_TOKENS = Set.of("completed", "success", "paid", "ok", "1");

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;
    private final GameParticipantRepository gameParticipantRepository;
    private final PaymentMapper paymentMapper;
    private final List<LegacyCheckoutProvider> paymentProviders;
    private final GameService gameService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final PaymentProvider paymentProvider;
    private final PlatformSettingsService platformSettingsService;

    public PaymentService(PaymentRepository paymentRepository,
                          BookingRepository bookingRepository,
                          UserRepository userRepository,
                          GameRepository gameRepository,
                          GameParticipantRepository gameParticipantRepository,
                          PaymentMapper paymentMapper,
                          List<LegacyCheckoutProvider> paymentProviders,
                          @Lazy GameService gameService,
                          NotificationService notificationService,
                          AuditService auditService,
                          PaymentProvider paymentProvider,
                          PlatformSettingsService platformSettingsService) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.gameRepository = gameRepository;
        this.gameParticipantRepository = gameParticipantRepository;
        this.paymentMapper = paymentMapper;
        this.paymentProviders = paymentProviders;
        this.gameService = gameService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.paymentProvider = paymentProvider;
        this.platformSettingsService = platformSettingsService;
    }

    /**
     * Charges a saved card for a booking and, if the money arrives, confirms the
     * booking through {@link #applySuccessfulBookingPayment}.
     *
     * <p>The provider is called first and the payment row is written afterwards
     * with its final status, so a row's amounts, split and transaction id are
     * correct from the moment it exists and never need rewriting. A declined
     * card produces a {@code FAILED} row and leaves the booking pending, to
     * expire on the usual hold timeout.
     *
     * @param amount tiyin to charge — the stadium minimum or the full price
     */
    @Transactional
    public Payment chargeForBooking(Booking booking, SavedCard card, long amount) {
        return chargeForBooking(booking, card, amount, Payment.PaymentType.BOOKING);
    }

    /** As {@link #chargeForBooking(Booking, SavedCard, long)}, for a chosen payment type. */
    @Transactional
    public Payment chargeForBooking(Booking booking, SavedCard card, long amount, Payment.PaymentType type) {
        int commissionPercent = platformSettingsService.current().getBookingCommissionPercent();
        long platformAmount = amount * commissionPercent / 100;
        long ownerAmount = amount - platformAmount;
        String reference = UUID.randomUUID().toString();

        ProviderTransaction txn = paymentProvider.charge(new ChargeCommand(
                card.getProviderToken(), amount, ownerAmount, platformAmount, reference));

        Payment payment = paymentRepository.save(Payment.builder()
                .booking(booking)
                .user(booking.getUser())
                .amount(amount)
                .ownerAmount(ownerAmount)
                .platformAmount(platformAmount)
                .commissionPercent(commissionPercent)
                .method(Payment.Method.CARD)
                .paymentType(type)
                .provider(Payment.Provider.ATMOS)
                .providerTxnId(txn.providerTxnId())
                .status(txn.failed() ? Payment.Status.FAILED : Payment.Status.PAID)
                .build());

        if (txn.failed()) {
            log.info("Card charge declined: bookingId={} amount={} reference={}",
                    booking.getId(), amount, reference);
            return payment;
        }

        auditService.log(booking.getUser() == null ? null : booking.getUser().getId(),
                "PAYMENT_COMPLETED", "payment", payment.getId(),
                "{\"amount\":" + amount + ",\"method\":\"card\",\"type\":\"" + type.getValue() + "\"}");
        return applySuccessfulBookingPayment(booking, payment);
    }

    /**
     * The one place a booking becomes confirmed once money has arrived — used by
     * the synchronous charge today and by a provider webhook later.
     *
     * <p>If the hold already expired, the slot is only still ours when nothing
     * else has taken it; otherwise the payment is refunded in full and the
     * player told. The database's overlap constraint is the final arbiter, and a
     * violation is caught here and refunded too: an overlap error must never
     * travel back out to the payment provider.
     */
    @Transactional
    public Payment applySuccessfulBookingPayment(Booking booking, Payment payment) {
        if ("expired".equals(booking.getStatus())
                && bookingRepository.existsActiveOverlap(
                        booking.getStadium().getId(), booking.getStartsAt(), booking.getEndsAt())) {
            return refundBecauseSlotIsGone(booking, payment);
        }

        long paid = (booking.getPaidAmount() == null ? 0L : booking.getPaidAmount()) + payment.getAmount();
        String previousStatus = booking.getStatus();
        booking.setPaidAmount(paid);
        booking.setPaymentStatus(paid >= booking.getPrice()
                ? Booking.PaymentStatus.PAID
                : Booking.PaymentStatus.PARTIALLY_PAID);
        booking.setStatus("confirmed");
        booking.setExpiresAt(null);

        try {
            bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            // Someone took the slot between the check above and this write.
            booking.setStatus(previousStatus);
            booking.setPaidAmount(paid - payment.getAmount());
            booking.setPaymentStatus(booking.getPaidAmount() > 0
                    ? Booking.PaymentStatus.PARTIALLY_PAID
                    : Booking.PaymentStatus.UNPAID);
            return refundBecauseSlotIsGone(booking, payment);
        }

        auditService.log(booking.getUser() == null ? null : booking.getUser().getId(),
                "BOOKING_CONFIRMED", "booking", booking.getId(),
                "{\"paidAmount\":" + paid + ",\"paymentStatus\":\""
                        + booking.getPaymentStatus().getValue() + "\"}");
        notifyBookingConfirmed(booking);
        log.info("Booking confirmed by payment: id={} paid={} of {} status={}",
                booking.getId(), paid, booking.getPrice(), booking.getPaymentStatus().getValue());
        return payment;
    }

    /**
     * Refunds a card payment in full — the only refund shape ATMOS supports, and
     * therefore the only one we offer. Cash is never refunded through the
     * platform; the owner settles that directly.
     */
    @Transactional
    public Payment refundInFull(Payment original, String reason) {
        if (original.getMethod() == Payment.Method.CASH) {
            log.info("Cash payment {} is not refunded through the platform", original.getId());
            return null;
        }
        if (original.getStatus() == Payment.Status.REFUNDED) {
            return null; // already given back
        }

        paymentProvider.refund(original.getProviderTxnId());

        Payment refund = paymentRepository.save(Payment.builder()
                .booking(original.getBooking())
                .game(original.getGame())
                .user(original.getUser())
                .amount(-Math.abs(original.getAmount()))
                .ownerAmount(-Math.abs(original.getOwnerAmount()))
                .platformAmount(-Math.abs(original.getPlatformAmount()))
                .commissionPercent(original.getCommissionPercent())
                .method(original.getMethod())
                .paymentType(Payment.PaymentType.REFUND)
                .provider(original.getProvider())
                .providerTxnId(original.getProviderTxnId() + "-refund")
                .status(Payment.Status.REFUNDED)
                .refundReason(reason)
                .refundOfPayment(original)
                .build());

        original.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(original);

        auditService.log(original.getUser() == null ? null : original.getUser().getId(),
                "REFUND_CREATED", "payment", refund.getId(),
                "{\"originalPaymentId\":\"" + original.getId() + "\",\"amount\":" + refund.getAmount() + "}");
        log.info("Refunded in full: originalId={} amount={} reason={}",
                original.getId(), refund.getAmount(), reason);
        return refund;
    }

    @Transactional
    public PaymentInitResponse initiateBookingPayment(UUID bookingId, String providerName, UUID userId) {
        Payment.Provider provider = parseCheckoutProvider(providerName);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        if (!"pending".equals(booking.getStatus())) {
            throw new BadRequestException("Booking is not awaiting payment");
        }

        Payment payment = Payment.builder()
                .booking(booking)
                .user(booking.getUser())
                .amount(booking.getPrice())
                .paymentType(Payment.PaymentType.BOOKING)
                .provider(provider)
                .status(Payment.Status.PENDING)
                .build();
        Payment saved = withMockTxnId(paymentRepository.saveAndFlush(payment));

        String checkoutUrl = resolveProvider(provider).createCheckoutUrl(saved.getId());
        log.info("Payment initiated: id={} bookingId={} userId={} provider={}",
                saved.getId(), bookingId, userId, provider.getValue());
        return initResponse(saved, provider, checkoutUrl);
    }

    @Transactional
    public PaymentInitResponse initiateGamePayment(UUID gameId, String providerName, UUID userId) {
        Payment.Provider provider = parseCheckoutProvider(providerName);

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found"));
        GameParticipant participant = gameParticipantRepository.findByGameIdAndUserId(gameId, userId)
                .orElseThrow(() -> new BadRequestException("Join the game before paying"));

        Payment payment = Payment.builder()
                .game(game)
                .user(participant.getUser())
                .amount(gameShareAmount(game, participant))
                .paymentType(Payment.PaymentType.GAME_SHARE)
                .provider(provider)
                .status(Payment.Status.PENDING)
                .build();
        Payment saved = withMockTxnId(paymentRepository.saveAndFlush(payment));

        participant.setPayment(saved);
        gameParticipantRepository.save(participant);

        String checkoutUrl = resolveProvider(provider).createCheckoutUrl(saved.getId());
        log.info("Game payment initiated: id={} gameId={} userId={} amount={} provider={}",
                saved.getId(), gameId, userId, saved.getAmount(), provider.getValue());
        return initResponse(saved, provider, checkoutUrl);
    }

    @Transactional
    public Payment handleProviderCallback(String providerName, String providerTxnId, String providerStatus) {
        Payment.Provider provider = parseProvider(providerName);
        Payment payment = paymentRepository.findByProviderAndProviderTxnId(provider, providerTxnId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment found for transaction " + providerTxnId));

        if (payment.getStatus() == Payment.Status.COMPLETED) {
            return payment; // idempotent: duplicate webhook for an already-completed payment
        }

        if (isSuccess(providerStatus)) {
            payment.setStatus(Payment.Status.COMPLETED);
            paymentRepository.save(payment);
            auditService.log(payment.getUser().getId(), "PAYMENT_COMPLETED", "payment", payment.getId(),
                    "{\"amount\":" + payment.getAmount() + ",\"provider\":\"" + payment.getProvider().getValue() + "\"}");
            onPaymentCompleted(payment);
            log.info("Payment completed: id={} type={} provider={} txn={}",
                    payment.getId(), payment.getPaymentType().getValue(), provider.getValue(), providerTxnId);
        } else {
            payment.setStatus(Payment.Status.FAILED);
            paymentRepository.save(payment);
            log.info("Payment failed: id={} provider={} txn={} providerStatus={}",
                    payment.getId(), provider.getValue(), providerTxnId, providerStatus);
        }
        return payment;
    }

    @Transactional
    public Payment createRefund(UUID originalPaymentId, long refundAmount, String reason) {
        Payment original = paymentRepository.findById(originalPaymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (original.getStatus() != Payment.Status.COMPLETED) {
            throw new BadRequestException("Only a completed payment can be refunded");
        }

        // The split is carried over in proportion so owner + platform still adds
        // up to the refunded amount, which the DB CHECK requires.
        long amount = -Math.abs(refundAmount);
        long platformPart = original.getAmount() == 0 ? 0
                : amount * original.getPlatformAmount() / original.getAmount();
        Payment refund = Payment.builder()
                .booking(original.getBooking())
                .game(original.getGame())
                .user(original.getUser())
                .amount(amount)
                .ownerAmount(amount - platformPart)
                .platformAmount(platformPart)
                .commissionPercent(original.getCommissionPercent())
                .method(original.getMethod())
                .paymentType(Payment.PaymentType.REFUND)
                .provider(original.getProvider())
                .status(Payment.Status.COMPLETED)
                .refundReason(reason)
                .refundOfPayment(original)
                .build();
        Payment savedRefund = paymentRepository.save(refund);

        original.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(original);

        auditService.log(original.getUser().getId(), "REFUND_CREATED", "payment", savedRefund.getId(),
                "{\"originalPaymentId\":\"" + originalPaymentId + "\",\"amount\":" + savedRefund.getAmount() + "}");
        log.info("Refund created: id={} originalId={} amount={}",
                savedRefund.getId(), originalPaymentId, savedRefund.getAmount());
        return savedRefund;
    }

    /** Refund the completed booking payment, if one exists. Called by BookingService on cancellation. */
    @Transactional
    public Payment refundBookingPayment(UUID bookingId, long refundAmount, String reason) {
        Optional<Payment> original = paymentRepository.findByBookingIdAndPaymentTypeAndStatus(
                bookingId, Payment.PaymentType.BOOKING, Payment.Status.COMPLETED);
        if (original.isEmpty()) {
            log.warn("No completed booking payment to refund for booking {}", bookingId);
            return null;
        }
        return createRefund(original.get().getId(), refundAmount, reason);
    }

    /** Refund a payment in full by its id. Called by GameService for participant refunds. */
    @Transactional
    public Payment refundFullPayment(UUID paymentId, String reason) {
        Payment original = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        return createRefund(paymentId, Math.abs(original.getAmount()), reason);
    }

    @Transactional
    public Payment recordCashPayment(UUID bookingId, UUID ownerUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getStadium().getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        User owner = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Cash never passes through the provider, so it carries no commission:
        // the owner has the money in hand already.
        long amount = booking.getRemainingAmount() > 0 ? booking.getRemainingAmount() : booking.getPrice();
        Payment payment = Payment.builder()
                .booking(booking)
                .user(owner)
                .amount(amount)
                .ownerAmount(amount)
                .platformAmount(0L)
                .commissionPercent(0)
                .method(Payment.Method.CASH)
                .paymentType(Payment.PaymentType.BOOKING)
                .provider(Payment.Provider.CASH)
                .status(Payment.Status.PAID)
                .build();
        Payment saved = paymentRepository.save(payment);

        auditService.log(ownerUserId, "PAYMENT_COMPLETED", "payment", saved.getId(),
                "{\"amount\":" + saved.getAmount() + ",\"method\":\"cash\"}");
        applySuccessfulBookingPayment(booking, saved);
        log.info("Cash payment recorded: id={} bookingId={} ownerId={}", saved.getId(), bookingId, ownerUserId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> getUserPayments(UUID userId, Pageable pageable) {
        return paymentRepository.findByUserId(userId, pageable).map(paymentMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!payment.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This payment does not belong to you");
        }
        return paymentMapper.toResponse(payment);
    }

    // ---- Helpers ----

    private void onPaymentCompleted(Payment payment) {
        if (payment.getPaymentType() == Payment.PaymentType.BOOKING && payment.getBooking() != null) {
            // Same single path as the card charge: expiry and overlap are checked there.
            applySuccessfulBookingPayment(payment.getBooking(), payment);
        } else if (payment.getPaymentType() == Payment.PaymentType.GAME_SHARE && payment.getGame() != null) {
            gameService.handleGamePaymentConfirmed(payment.getGame().getId(), payment.getUser().getId());
        }
    }

    /**
     * The hold lapsed and the slot has gone to someone else: give the money
     * straight back and tell the player. The booking stays expired.
     */
    private Payment refundBecauseSlotIsGone(Booking booking, Payment payment) {
        String reason = "The slot was taken before this payment arrived";
        refundInFull(payment, reason);
        if (booking.getUser() != null) {
            UUID uid = booking.getUser().getId();
            String stadiumName = booking.getStadium().getName();
            safeNotify(() -> notificationService.notify(uid, Notification.Type.BOOKING_CANCELLED,
                    "Бронь не подтверждена",
                    "Слот в «" + stadiumName + "» был занят до поступления оплаты. Возврат средств оформлен.",
                    Notification.ReferenceType.BOOKING, booking.getId()));
        }
        log.info("Payment refunded, slot no longer available: bookingId={} paymentId={}",
                booking.getId(), payment.getId());
        return payment;
    }

    private void notifyBookingConfirmed(Booking booking) {
        if (booking.getUser() == null) {
            return;
        }
        UUID uid = booking.getUser().getId();
        String stadiumName = booking.getStadium().getName();
        safeNotify(() -> notificationService.notify(uid,
                Notification.Type.BOOKING_CONFIRMED,
                "Бронь подтверждена",
                "Ваша бронь в «" + stadiumName + "» подтверждена.",
                Notification.ReferenceType.BOOKING, booking.getId()));
    }

    /** Notifications are best-effort — a failure must never undo a payment. */
    private void safeNotify(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("Notification failed: {}", ex.toString());
        }
    }

    /**
     * Mock txn id derived from our payment id; the webhook echoes it back.
     * TODO: Replace with the transaction id returned by the real provider.
     */
    private Payment withMockTxnId(Payment saved) {
        saved.setProviderTxnId("mock-" + saved.getId());
        return paymentRepository.save(saved);
    }

    private long gameShareAmount(Game game, GameParticipant participant) {
        long fee = game.getServiceFeePerPerson() == null ? 0 : game.getServiceFeePerPerson();
        long total = game.getTotalPrice();
        if ("team_vs_team".equals(game.getType())) {
            int pct = participant.getSide() == GameParticipant.Side.B
                    ? 100 - game.getCreatorSharePct()
                    : game.getCreatorSharePct();
            return total * pct / 100 + fee;
        }
        int players = game.getMaxPlayers() == null || game.getMaxPlayers() < 1 ? 1 : game.getMaxPlayers();
        return total / players + fee;
    }

    private PaymentInitResponse initResponse(Payment saved, Payment.Provider provider, String checkoutUrl) {
        return PaymentInitResponse.builder()
                .paymentId(saved.getId())
                .checkoutUrl(checkoutUrl)
                .provider(provider.getValue())
                .build();
    }

    private Payment.Provider parseCheckoutProvider(String providerName) {
        Payment.Provider provider = parseProvider(providerName);
        if (provider == Payment.Provider.CASH) {
            throw new BadRequestException("Cash is recorded by the owner, not initiated as a checkout");
        }
        return provider;
    }

    private Payment.Provider parseProvider(String providerName) {
        try {
            return Payment.Provider.fromValue(providerName);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("Unknown payment provider: " + providerName);
        }
    }

    private LegacyCheckoutProvider resolveProvider(Payment.Provider provider) {
        return paymentProviders.stream()
                .filter(p -> p.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unsupported provider: " + provider.getValue()));
    }

    private boolean isSuccess(String providerStatus) {
        return providerStatus == null
                || providerStatus.isBlank()
                || SUCCESS_TOKENS.contains(providerStatus.trim().toLowerCase(Locale.ROOT));
    }
}
