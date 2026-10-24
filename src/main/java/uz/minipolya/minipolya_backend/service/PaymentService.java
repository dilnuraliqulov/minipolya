package uz.minipolya.minipolya_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
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
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.LegacyCheckoutProvider;
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

    public PaymentService(PaymentRepository paymentRepository,
                          BookingRepository bookingRepository,
                          UserRepository userRepository,
                          GameRepository gameRepository,
                          GameParticipantRepository gameParticipantRepository,
                          PaymentMapper paymentMapper,
                          List<LegacyCheckoutProvider> paymentProviders,
                          @Lazy GameService gameService,
                          NotificationService notificationService,
                          AuditService auditService) {
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

        Payment refund = Payment.builder()
                .booking(original.getBooking())
                .game(original.getGame())
                .user(original.getUser())
                .amount(-Math.abs(refundAmount))
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

        Payment payment = Payment.builder()
                .booking(booking)
                .user(owner)
                .amount(booking.getPrice())
                .paymentType(Payment.PaymentType.BOOKING)
                .provider(Payment.Provider.CASH)
                .status(Payment.Status.COMPLETED)
                .build();
        Payment saved = paymentRepository.save(payment);

        auditService.log(ownerUserId, "PAYMENT_COMPLETED", "payment", saved.getId(),
                "{\"amount\":" + saved.getAmount() + ",\"provider\":\"cash\"}");
        confirmPaidBooking(booking);
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
            confirmPaidBooking(payment.getBooking());
        } else if (payment.getPaymentType() == Payment.PaymentType.GAME_SHARE && payment.getGame() != null) {
            gameService.handleGamePaymentConfirmed(payment.getGame().getId(), payment.getUser().getId());
        }
    }

    private void confirmPaidBooking(Booking booking) {
        booking.setStatus("confirmed");
        booking.setExpiresAt(null);
        bookingRepository.save(booking);
        UUID bookingUserId = booking.getUser() == null ? null : booking.getUser().getId();
        auditService.log(bookingUserId, "BOOKING_CONFIRMED", "booking", booking.getId());
        if (booking.getUser() != null) {
            try {
                notificationService.notify(booking.getUser().getId(),
                        Notification.Type.BOOKING_CONFIRMED,
                        "Бронь подтверждена",
                        "Ваша бронь в «" + booking.getStadium().getName() + "» подтверждена.",
                        Notification.ReferenceType.BOOKING, booking.getId());
            } catch (Exception ex) {
                log.warn("Booking-confirmed notification failed: {}", ex.toString());
            }
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
