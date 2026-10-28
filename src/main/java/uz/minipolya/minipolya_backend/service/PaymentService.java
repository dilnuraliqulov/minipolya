package uz.minipolya.minipolya_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.HoldCancelledException;
import uz.minipolya.minipolya_backend.provider.HoldExpiredException;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.LegacyCheckoutProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PendingCharge;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.provider.TransactionClosedException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.CardFeeSplit;

import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * Statuses that mean money actually arrived and has not been given back.
     * {@code COMPLETED} is only here for the legacy Payme/Click game rows.
     */
    private static final Set<Payment.Status> REFUNDABLE_STATUSES =
            Set.of(Payment.Status.PAID, Payment.Status.CAPTURED, Payment.Status.COMPLETED);

    /** Statuses that mean the money is the platform's and is not coming back. */
    private static final Set<Payment.Status> KEPT_STATUSES =
            Set.of(Payment.Status.PAID, Payment.Status.CAPTURED, Payment.Status.COMPLETED);

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
    private final CardPricing cardPricing;

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
        this.cardPricing = new CardPricing(platformSettingsService::current);
    }

    /**
     * Charges a saved card for a booking and, if the money arrives, confirms the
     * booking through {@link #applySuccessfulBookingPayment}.
     *
     * <p>The payment row is written first, {@code processing}, and its id is the
     * provider's {@code account}, so a retry can never take the money twice; only its
     * status and provider ids move afterwards. A declined card produces a {@code FAILED}
     * row and leaves the booking pending, to expire on the usual hold timeout. A lost
     * answer leaves the row {@code processing} for the reconcile job — never assumed
     * failed, never charged again.
     *
     * @param charge what the card is charged and how it splits — the stadium minimum or
     *               the full price, grossed up by the card fee
     */
    @Transactional
    public Payment chargeForBooking(Booking booking, SavedCard card, CardFeeSplit charge) {
        return chargeForBooking(booking, card, charge, Payment.PaymentType.BOOKING);
    }

    /** As {@link #chargeForBooking(Booking, SavedCard, CardFeeSplit)}, for a chosen payment type. */
    @Transactional
    public Payment chargeForBooking(Booking booking, SavedCard card, CardFeeSplit charge, Payment.PaymentType type) {
        int commissionPercent = platformSettingsService.current().getBookingCommissionPercent();
        Payment row = Payment.builder()
                .booking(booking)
                .user(booking.getUser())
                .amount(charge.playerAmount())
                .ownerAmount(charge.ownerGross())
                .platformAmount(charge.platformGross())
                .cardFeeExpected(charge.cardFee())
                .commissionPercent(commissionPercent)
                .method(Payment.Method.CARD)
                .paymentType(type)
                .provider(Payment.Provider.ATMOS)
                .status(Payment.Status.PROCESSING)
                .build();
        Payment payment = chargeCard(row, card, charge, booking.getStadium().getOwner());

        if (payment.getStatus() == Payment.Status.FAILED) {
            log.info("Card charge declined: bookingId={} paymentId={} amount={}",
                    booking.getId(), payment.getId(), payment.getAmount());
            return payment;
        }
        if (payment.getStatus() == Payment.Status.PROCESSING) {
            log.warn("Card charge outcome unknown, left for reconciliation: bookingId={} paymentId={}",
                    booking.getId(), payment.getId());
            return payment;
        }

        auditPaymentCompleted(payment, null);
        return applySuccessfulBookingPayment(booking, payment);
    }

    /**
     * Reserves one team's fee and owner advance on its leader's card when the
     * team takes a side of a team-vs-team game.
     *
     * <p>A hold, not a charge: a waiting game holds no slot, so the money is
     * only taken once an opponent turns up and the booking actually exists.
     * The ATMOS split carries the two parts apart — the advance is the owner's,
     * the fee is the platform's — and no booking commission is added on top,
     * because on a team game the fee <em>is</em> the platform's cut.
     *
     * <p>A declined card comes back as a {@code FAILED} row, not an exception.
     *
     * @param durationMinutes how long the provider keeps the money reserved
     */
    @Transactional
    public Payment holdForTeamGame(GameTeam gameTeam, SavedCard card, int durationMinutes) {
        CardFeeSplit split = joinSplit(gameTeam);
        Payment payment = holdCard(teamGameRow(gameTeam, split, Payment.PaymentType.TEAM_GAME),
                card, split, ownerOf(gameTeam), durationMinutes);

        log.info("Team game hold {}: gameTeamId={} paymentId={} amount={} minutes={}",
                payment.getStatus().getValue(), gameTeam.getId(), payment.getId(), split.playerAmount(),
                durationMinutes);
        return payment;
    }

    /**
     * Takes one team's money now: its fee plus advance when it joins
     * ({@code TEAM_GAME}), or its outstanding remainder later
     * ({@code REMAINDER}).
     *
     * <p>A remainder is all the owner's — the platform already took its fee
     * when the team joined, and charging a cut twice would double-count it.
     *
     * <p>A declined card comes back as a {@code FAILED} row, not an exception.
     */
    @Transactional
    public Payment chargeForTeamGame(GameTeam gameTeam, SavedCard card, Payment.PaymentType type) {
        CardFeeSplit split = type == Payment.PaymentType.REMAINDER
                ? cardPricing.split(gameTeam.getRemainderAmount(), 0L)
                : joinSplit(gameTeam);

        Payment payment = chargeCard(teamGameRow(gameTeam, split, type), card, split, ownerOf(gameTeam));

        log.info("Team game charge {}: gameTeamId={} paymentId={} type={} amount={}",
                payment.getStatus().getValue(), gameTeam.getId(), payment.getId(), type.getValue(),
                split.playerAmount());
        return payment;
    }

    /** What the player pays by card for this net amount (no split): a quote. */
    public long cardPrice(long net) {
        return cardPricing.playerAmount(net);
    }

    /**
     * What a team's card is charged (or held) on joining: {@code playerAmount(advance + fee)},
     * split so the owner nets the advance and the platform nets its fee after the card fee.
     */
    public CardFeeSplit joinSplit(GameTeam gameTeam) {
        return cardPricing.joinSplit(gameTeam);
    }

    /**
     * Settles a hold by taking the whole reserved amount. A hold that lapsed or was let go
     * throws {@link HoldExpiredException} / {@link HoldCancelledException}; the caller
     * decides what that means for its game.
     */
    @Transactional
    public Payment captureHeld(Payment held) {
        HoldOutcome outcome = paymentProvider.captureHold(held.getProviderTxnId());
        held.setStatus(Payment.Status.CAPTURED);
        if (outcome.commission() != null) {
            held.setProviderCommission(outcome.commission());
        }
        Payment saved = paymentRepository.save(held);
        log.info("Hold captured: paymentId={} amount={}", held.getId(), held.getAmount());
        return saved;
    }

    /**
     * The owner took one team's remainder in cash. Cash never passes through
     * the provider, so it carries no commission and is never refunded through
     * the platform — the owner settles that directly.
     */
    @Transactional
    public Payment recordCashTeamRemainder(GameTeam gameTeam, User owner) {
        long amount = gameTeam.getRemainderAmount();
        Payment payment = paymentRepository.save(Payment.builder()
                .game(gameTeam.getGame())
                .gameTeam(gameTeam)
                .user(owner)
                .amount(amount)
                .ownerAmount(amount)
                .platformAmount(0L)
                .commissionPercent(0)
                .method(Payment.Method.CASH)
                .paymentType(Payment.PaymentType.REMAINDER)
                .provider(Payment.Provider.CASH)
                .status(Payment.Status.PAID)
                .build());

        log.info("Team remainder taken in cash: gameTeamId={} amount={}", gameTeam.getId(), amount);
        return payment;
    }

    /** Settles every hold one team placed on one game. */
    @Transactional
    public void captureAllHeldTeamPayments(UUID gameTeamId) {
        for (Payment payment : paymentRepository.findByGameTeamId(gameTeamId)) {
            if (payment.getStatus() == Payment.Status.HELD) {
                captureHeld(payment);
            }
        }
    }

    /**
     * Gives one payment back in whichever way its state allows — "everything
     * back" for a team game.
     *
     * <p>A hold is released: no money ever left the card, so there is nothing
     * to record and no refund row is written. A payment that actually settled
     * is refunded in full through {@link #refundInFull}. A failed, released or
     * already-refunded row is left alone.
     *
     * @return the refund row, or {@code null} when a release or a no-op
     */
    @Transactional
    public Payment giveBackInFull(Payment original, String reason) {
        if (original.getStatus() == Payment.Status.HELD) {
            try {
                paymentProvider.releaseHold(original.getProviderTxnId());
            } catch (HoldCancelledException | HoldExpiredException ex) {
                // Already let go — released before, or lapsed at the provider. Either way
                // no money is reserved any more, which is all a release is for, so this
                // counts as success: the timeout job and a retry are idempotent.
                log.info("Hold {} was already let go ({}); recording it released",
                        original.getId(), ex.getCode());
            }
            Payment.Status statusBefore = original.getStatus();
            original.setStatus(Payment.Status.RELEASED);
            paymentRepository.save(original);
            // No money moved, so no refund row: the hold on the card is simply let go.
            auditService.record(AuditEntry.system(AuditAction.PAYMENT_RELEASED)
                    .entity(AuditEntityType.PAYMENT, original.getId(), AuditLabels.payment(original))
                    .change("status", statusBefore, original.getStatus())
                    .created("releasedAmount", original.getAmount())
                    .reason(reason));
            log.info("Hold released: paymentId={} amount={} reason={}",
                    original.getId(), original.getAmount(), reason);
            return null;
        }
        return refundInFull(original, reason);
    }

    /**
     * Everything one team put into one game, given back: holds released, settled
     * payments refunded in full.
     *
     * @return the tiyin the team no longer owes or has lost
     */
    @Transactional
    public long giveBackAllTeamPayments(UUID gameTeamId, String reason) {
        long returned = 0;
        for (Payment payment : paymentRepository.findByGameTeamId(gameTeamId)) {
            if (payment.getAmount() == null || payment.getAmount() <= 0) {
                continue; // a refund row is not itself refundable
            }
            long amount = payment.getAmount();
            Payment.Status before = payment.getStatus();
            giveBackInFull(payment, reason);
            if (before != payment.getStatus()) {
                returned += amount;
            }
        }
        log.info("Team payments given back: gameTeamId={} amount={} reason={}", gameTeamId, returned, reason);
        return returned;
    }

    /**
     * Keeps everything one team paid for a game — what a cancellation past the
     * refund deadline does. A hold is captured, because the money was promised
     * and is now owed; a payment that already settled simply stays.
     *
     * @return the tiyin the team does not get back
     */
    @Transactional
    public long keepAllTeamPayments(UUID gameTeamId) {
        long kept = 0;
        for (Payment payment : paymentRepository.findByGameTeamId(gameTeamId)) {
            if (payment.getAmount() == null || payment.getAmount() <= 0) {
                continue;
            }
            if (payment.getStatus() == Payment.Status.HELD) {
                captureHeld(payment);
            }
            if (KEPT_STATUSES.contains(payment.getStatus())) {
                kept += payment.getAmount();
            }
        }
        log.info("Team payments kept: gameTeamId={} amount={}", gameTeamId, kept);
        return kept;
    }

    /**
     * What {@link #giveBackAllTeamPayments} would return for this team, without
     * moving anything — the figure behind {@code refundIfCancelledNow}. Both
     * read the same rows, so a team is never shown a number it will not get.
     */
    @Transactional(readOnly = true)
    public long refundableTeamTotal(UUID gameTeamId) {
        return paymentRepository.findByGameTeamId(gameTeamId).stream()
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .filter(p -> p.getStatus() == Payment.Status.HELD
                        || REFUNDABLE_STATUSES.contains(p.getStatus()))
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .mapToLong(Payment::getAmount)
                .sum();
    }

    /** One team-game payment row, before the provider is called. Amounts and split never change. */
    private Payment teamGameRow(GameTeam gameTeam, CardFeeSplit split, Payment.PaymentType type) {
        return Payment.builder()
                .game(gameTeam.getGame())
                .gameTeam(gameTeam)
                .user(gameTeam.getLeader())
                .amount(split.playerAmount())
                .ownerAmount(split.ownerGross())
                .platformAmount(split.platformGross())
                .cardFeeExpected(split.cardFee())
                .commissionPercent(0)
                .method(Payment.Method.CARD)
                .paymentType(type)
                .provider(Payment.Provider.ATMOS)
                .status(Payment.Status.PROCESSING)
                .build();
    }

    // ---- The provider ----

    /**
     * Writes the row ({@code processing}; its id is the provider's {@code account}), then
     * creates and confirms the charge, and moves the row on to what the provider said.
     * A timeout leaves it {@code processing}: the money may or may not have moved, so
     * nothing here may assume either — PaymentReconcileJob asks the provider later.
     */
    private Payment chargeCard(Payment row, SavedCard card, CardFeeSplit split, User owner) {
        Payment saved = paymentRepository.save(row);
        try {
            PendingCharge pending = paymentProvider.charge(ChargeRequest.withToken(
                    splitsFor(owner, split), card.getProviderToken(), saved.getId().toString()));
            saved.setProviderTxnId(String.join(",", pending.transactionIds()));
            applyChargeOutcome(saved, paymentProvider.confirmCharge(pending.transactionIds(), null));
        } catch (ProviderTimeoutException ex) {
            log.warn("Payment {} left processing: {}", saved.getId(), ex.getMessage());
        }
        return paymentRepository.save(saved);
    }

    /** As {@link #chargeCard}, for a hold of {@code minutes}. */
    private Payment holdCard(Payment row, SavedCard card, CardFeeSplit split, User owner, int minutes) {
        Payment saved = paymentRepository.save(row);
        try {
            HoldOutcome created = paymentProvider.hold(new HoldRequest(
                    splitsFor(owner, split), card.getProviderToken(), saved.getId().toString(), minutes));
            saved.setProviderTxnId(created.holdId());
            saved.setHoldExpiresAt(created.holdTill());
            applyHoldOutcome(saved, paymentProvider.confirmHold(created.holdId(), null));
        } catch (ProviderTimeoutException ex) {
            log.warn("Payment {} left processing: {}", saved.getId(), ex.getMessage());
        }
        return paymentRepository.save(saved);
    }

    /** Moves a charge row on to the provider's answer. PENDING leaves it processing. */
    void applyChargeOutcome(Payment payment, ChargeOutcome outcome) {
        switch (outcome.status()) {
            case SUCCESS -> {
                payment.setStatus(Payment.Status.PAID);
                payment.setProviderSuccessTxnIds(String.join(",", outcome.successTransIds()));
                payment.setProviderCommission(outcome.commission());
                payment.setOfdUrl(outcome.ofdUrl());
            }
            case FAILED -> {
                payment.setStatus(Payment.Status.FAILED);
                log.info("Payment {} declined by the provider: code={}", payment.getId(), outcome.errorCode());
            }
            case REVERSED -> payment.setStatus(Payment.Status.REFUNDED);
            default -> {
                // still in flight: stays processing
            }
        }
    }

    /** Moves a hold row on to the provider's answer. PENDING leaves it processing. */
    void applyHoldOutcome(Payment payment, HoldOutcome outcome) {
        if (outcome.holdTill() != null) {
            payment.setHoldExpiresAt(outcome.holdTill());
        }
        switch (outcome.status()) {
            case HELD -> payment.setStatus(Payment.Status.HELD);
            case CAPTURED -> {
                payment.setStatus(Payment.Status.CAPTURED);
                payment.setProviderCommission(outcome.commission());
            }
            case RELEASED -> payment.setStatus(Payment.Status.RELEASED);
            case FAILED -> {
                payment.setStatus(Payment.Status.FAILED);
                log.info("Hold {} declined by the provider: code={}", payment.getId(), outcome.errorCode());
            }
            default -> {
                // still in flight: stays processing
            }
        }
    }

    /**
     * Gives a settled card payment back in full: every split's transaction is reversed.
     * One already reversed (TransactionClosed) is done, not an error, so a retry is safe.
     *
     * <p>TODO(ATMOS): a captured hold has no success transaction id in the documented
     * answers; it is reversed by its hold id here until ATMOS says what to send.
     */
    private void reverseInFull(Payment original, String reason) {
        List<String> ids = original.getProviderSuccessTxnIds() == null || original.getProviderSuccessTxnIds().isBlank()
                ? List.of(original.getProviderTxnId())
                : Arrays.asList(original.getProviderSuccessTxnIds().split(","));
        for (String id : ids) {
            try {
                paymentProvider.reverse(id, reason);
            } catch (TransactionClosedException ex) {
                log.info("Transaction of payment {} was already reversed", original.getId());
            }
        }
    }

    /**
     * The owner's grossed-up share to the owner's ATMOS recipient, the platform's to the
     * platform; a share of zero is left out.
     */
    private List<Split> splitsFor(User owner, CardFeeSplit split) {
        List<Split> splits = new ArrayList<>(2);
        if (split.ownerGross() > 0) {
            splits.add(Split.owner(owner == null ? null : owner.getAtmosRecipientId(), split.ownerGross()));
        }
        if (split.platformGross() > 0) {
            splits.add(Split.platform(split.platformGross()));
        }
        return splits;
    }

    private User ownerOf(GameTeam gameTeam) {
        return gameTeam.getGame() == null || gameTeam.getGame().getStadium() == null
                ? null : gameTeam.getGame().getStadium().getOwner();
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

        // The booking counts what the payment is worth to the owner, not what the card fee added.
        long paidBefore = booking.getPaidAmount() == null ? 0L : booking.getPaidAmount();
        long paid = paidBefore + payment.netAmount();
        String previousStatus = booking.getStatus();
        Booking.PaymentStatus paymentStatusBefore = booking.getPaymentStatus();
        booking.setPaidAmount(paid);
        booking.setPaymentStatus(paid >= booking.getPrice()
                ? Booking.PaymentStatus.PAID
                : Booking.PaymentStatus.PARTIALLY_PAID);
        // A remainder can arrive after the game was played: paying it must not
        // drag a completed booking back to confirmed.
        if (!"completed".equals(previousStatus)) {
            booking.setStatus("confirmed");
        }
        booking.setExpiresAt(null);

        try {
            bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            // Someone took the slot between the check above and this write.
            booking.setStatus(previousStatus);
            booking.setPaidAmount(paid - payment.netAmount());
            booking.setPaymentStatus(booking.getPaidAmount() > 0
                    ? Booking.PaymentStatus.PARTIALLY_PAID
                    : Booking.PaymentStatus.UNPAID);
            return refundBecauseSlotIsGone(booking, payment);
        }

        // Only when the money is what confirmed it. A later payment on a live booking
        // (a remainder, the owner's cash) is recorded by that action's own entry.
        if (!"confirmed".equals(previousStatus) && "confirmed".equals(booking.getStatus())) {
            auditService.record(AuditEntry.system(AuditAction.BOOKING_CONFIRMED)
                    .entity(AuditEntityType.BOOKING, booking.getId(), AuditLabels.booking(booking))
                    .change("status", previousStatus, booking.getStatus())
                    .change("paidAmount", paidBefore, booking.getPaidAmount())
                    .change("paymentStatus", paymentStatusBefore, booking.getPaymentStatus())
                    .reason("Payment received"));
        }
        notifyBookingConfirmed(booking);
        log.info("Booking confirmed by payment: id={} paid={} of {} status={}",
                booking.getId(), paid, booking.getPrice(), booking.getPaymentStatus().getValue());
        return payment;
    }

    /**
     * The one refund method. ATMOS cannot refund part of a transaction, so a
     * refund is always for the whole payment; a partial refund simply does not
     * exist in this system. Cash is never refunded through the platform — the
     * owner settles that directly — and a payment that never succeeded, or was
     * already given back, is skipped.
     *
     * @return the new negative refund row, or {@code null} if there was nothing
     *         to refund
     */
    @Transactional
    public Payment refundInFull(Payment original, String reason) {
        if (original.getMethod() == Payment.Method.CASH) {
            log.info("Cash payment {} is not refunded through the platform", original.getId());
            return null;
        }
        if (!REFUNDABLE_STATUSES.contains(original.getStatus())) {
            return null; // never settled, or already given back
        }
        if (original.getAmount() == null || original.getAmount() <= 0) {
            return null; // a refund row itself is not refundable
        }

        // Only the card provider can give money back. Legacy Payme/Click rows
        // predate it and are reversed on paper only, as they always were.
        if (original.getProvider() == Payment.Provider.ATMOS) {
            reverseInFull(original, reason);
        }

        Payment refund = paymentRepository.save(Payment.builder()
                .booking(original.getBooking())
                .game(original.getGame())
                // Carried over so a team game's refund stays in that team's ledger.
                .gameTeam(original.getGameTeam())
                .user(original.getUser())
                // The whole gross comes back, the card fee included.
                .amount(-Math.abs(original.getAmount()))
                .ownerAmount(-Math.abs(original.getOwnerAmount()))
                .platformAmount(-Math.abs(original.getPlatformAmount()))
                .cardFeeExpected(-Math.abs(original.getCardFeeExpected() == null ? 0L : original.getCardFeeExpected()))
                .commissionPercent(original.getCommissionPercent())
                .method(original.getMethod())
                .paymentType(Payment.PaymentType.REFUND)
                .provider(original.getProvider())
                .providerTxnId(original.getProviderTxnId() == null
                        ? null : original.getProviderTxnId() + "-refund")
                .status(Payment.Status.REFUNDED)
                .refundReason(reason)
                .refundOfPayment(original)
                .build());

        Payment.Status statusBefore = original.getStatus();
        original.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(original);

        // On the payment that was refunded; the new negative row is named in the changes.
        auditService.record(AuditEntry.system(AuditAction.REFUND_CREATED)
                .entity(AuditEntityType.PAYMENT, original.getId(), AuditLabels.payment(original))
                .change("status", statusBefore, original.getStatus())
                .created("refundAmount", refund.getAmount())
                .created("refundPaymentId", refund.getId())
                .reason(reason));
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
        if ("team_vs_team".equals(game.getType())) {
            throw new BadRequestException(
                    "A team game is paid when creating or joining it, not through a checkout");
        }
        GameParticipant participant = gameParticipantRepository.findByGameIdAndUserId(gameId, userId)
                .orElseThrow(() -> new BadRequestException("Join the game before paying"));

        CardFeeSplit share = gameShareSplit(game);
        Payment payment = Payment.builder()
                .game(game)
                .user(participant.getUser())
                .amount(share.playerAmount())
                .ownerAmount(share.ownerGross())
                .platformAmount(share.platformGross())
                .cardFeeExpected(share.cardFee())
                .commissionPercent(0)
                .method(Payment.Method.CARD)
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
            Payment.Status statusBefore = payment.getStatus();
            payment.setStatus(Payment.Status.COMPLETED);
            paymentRepository.save(payment);
            auditPaymentCompleted(payment, statusBefore);
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

    /**
     * Every card payment of a booking that can still be given back. Cash is
     * excluded — the owner settles that off-platform — as are failed rows and
     * ones already refunded.
     *
     * <p>This is the list a cancellation refunds, so summing it answers "what
     * would I get back if I cancelled now?" without guessing.
     */
    /**
     * Money arrived — reported by the provider, so SYSTEM. {@code statusBefore} is null for
     * a synchronous charge, whose row is born settled. Never the card token or number.
     */
    private void auditPaymentCompleted(Payment payment, Payment.Status statusBefore) {
        auditService.record(AuditEntry.system(AuditAction.PAYMENT_COMPLETED)
                .entity(AuditEntityType.PAYMENT, payment.getId(), AuditLabels.payment(payment))
                .change("status", statusBefore, payment.getStatus())
                .created("amount", payment.getAmount())
                .created("method", payment.getMethod())
                .created("provider", payment.getProvider()));
    }

    @Transactional(readOnly = true)
    public List<Payment> refundableCardPayments(UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId).stream()
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .filter(p -> REFUNDABLE_STATUSES.contains(p.getStatus()))
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .toList();
    }

    /** Tiyin that would come back if this booking's card payments were refunded. */
    @Transactional(readOnly = true)
    public long refundableCardTotal(UUID bookingId) {
        return refundableCardPayments(bookingId).stream()
                .mapToLong(Payment::getAmount)
                .sum();
    }

    /**
     * Refunds every card payment of a booking in full — what both a player
     * cancellation inside the window and any owner or admin cancellation do.
     *
     * @return the tiyin actually given back
     */
    @Transactional
    public long refundAllCardPayments(UUID bookingId, String reason) {
        long refunded = 0;
        for (Payment original : refundableCardPayments(bookingId)) {
            Payment refund = refundInFull(original, reason);
            if (refund != null) {
                refunded += Math.abs(refund.getAmount());
            }
        }
        if (refunded == 0) {
            log.info("Nothing to refund for booking {}", bookingId);
        }
        return refunded;
    }

    @Transactional
    public Payment recordCashPayment(UUID bookingId, UUID ownerUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getStadium().getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        // The player may have paid the rest from a saved card first; whichever
        // got there first wins and the other is refused.
        if (booking.getPaymentStatus() == Booking.PaymentStatus.PAID) {
            throw new BadRequestException("Already paid");
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

        long paidBefore = booking.getPaidAmount() == null ? 0L : booking.getPaidAmount();
        Booking.PaymentStatus statusBefore = booking.getPaymentStatus();
        applySuccessfulBookingPayment(booking, saved);
        auditService.record(AuditEntry.owner(ownerUserId, AuditAction.CASH_RECEIVED)
                .entity(AuditEntityType.BOOKING, booking.getId(), AuditLabels.booking(booking))
                .change("paidAmount", paidBefore, booking.getPaidAmount())
                .change("paymentStatus", statusBefore, booking.getPaymentStatus()));
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

    /**
     * One player's share of a casual game, by card: the pitch share is the owner's and
     * the service fee the platform's, both grossed up — {@code playerAmount(share + fee)}.
     * Team games never reach here — their money is held on creation and charged on
     * joining, per team rather than per player, by {@code TeamGameService}.
     */
    public CardFeeSplit gameShareSplit(Game game) {
        return cardPricing.gameShareSplit(game);
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
