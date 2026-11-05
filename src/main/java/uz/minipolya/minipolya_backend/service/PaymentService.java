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
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ProviderUnavailableException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.AtmosCodes;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.HoldCancelledException;
import uz.minipolya.minipolya_backend.provider.HoldExpiredException;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.LegacyCheckoutProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.PendingCharge;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.provider.TransactionClosedException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
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
    private final OwnerLegalDetailsRepository ownerLegalDetailsRepository;
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
                          PlatformSettingsService platformSettingsService,
                          OwnerLegalDetailsRepository ownerLegalDetailsRepository) {
        this.ownerLegalDetailsRepository = ownerLegalDetailsRepository;
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
     * @param charge what the card is charged — the deposit, the player price D or the
     *               remainder — all of it the owner's: the platform takes nothing from a booking
     */
    @Transactional
    public Payment chargeForBooking(Booking booking, SavedCard card, CardFeeSplit charge) {
        return chargeForBooking(booking, card, charge, Payment.PaymentType.BOOKING);
    }

    /** As {@link #chargeForBooking(Booking, SavedCard, CardFeeSplit)}, for a chosen payment type. */
    @Transactional
    public Payment chargeForBooking(Booking booking, SavedCard card, CardFeeSplit charge, Payment.PaymentType type) {
        Payment row = Payment.builder()
                .booking(booking)
                .user(booking.getUser())
                .amount(charge.playerAmount())
                .ownerAmount(charge.ownerAmount())
                .platformAmount(charge.platformAmount())
                .cardFeeExpected(charge.atmosFee())
                .commissionPercent(0)
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
                ? cardPricing.teamRemainderSplit(gameTeam)
                : joinSplit(gameTeam);

        Payment payment = chargeCard(teamGameRow(gameTeam, split, type), card, split, ownerOf(gameTeam));

        log.info("Team game charge {}: gameTeamId={} paymentId={} type={} amount={}",
                payment.getStatus().getValue(), gameTeam.getId(), payment.getId(), type.getValue(),
                split.playerAmount());
        return payment;
    }

    // ---- Committed steps: each call is its own step, committed before the next one ----
    //
    // A team joining a game moves money in several provider calls. Each step below records
    // where it got to before (or right after) talking to the provider, and the join commits
    // after every step, so a crash half-way leaves rows the reconcile job can finish —
    // never money that moved with no record of it.

    /** How one committed step ended. */
    public enum StepOutcome {
        /** The provider did it. */
        DONE,
        /** The provider refused outright; nothing exists there. */
        REFUSED,
        /** The provider could not be reached; nothing exists there. */
        UNAVAILABLE,
        /** No answer: it may or may not have happened. The row says where it stands. */
        UNKNOWN
    }

    /** Step 0 of a join: the joining team's {@code processing} row, written before any provider call. */
    @Transactional
    public Payment openJoinCharge(GameTeam joining) {
        CardFeeSplit split = joinSplit(joining);
        Payment row = paymentRepository.save(teamGameRow(joining, split, Payment.PaymentType.TEAM_GAME));
        log.info("Join charge opened: gameTeamId={} paymentId={} amount={}",
                joining.getId(), row.getId(), split.playerAmount());
        return row;
    }

    /**
     * Step 1: the provider creates the charge, and its transaction ids are saved — so that
     * once the next step has taken the money, the reconcile job can always ask about it.
     */
    @Transactional
    public StepOutcome createCommittedCharge(UUID paymentId, SavedCard card) {
        Payment row = paymentRepository.findForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        try {
            PendingCharge pending = paymentProvider.charge(ChargeRequest.withToken(
                    splitsOf(row), card.getProviderToken(), row.getId().toString()));
            row.setProviderTxnId(String.join(",", pending.transactionIds()));
            paymentRepository.save(row);
            return StepOutcome.DONE;
        } catch (ProviderTimeoutException ex) {
            // Never acknowledged: the reconcile job marks a row without transaction ids failed.
            log.warn("Charge creation for payment {} unanswered", paymentId);
            return StepOutcome.UNKNOWN;
        } catch (PaymentProviderException ex) {
            row.setStatus(Payment.Status.FAILED);
            row.setFailureReason(failureReason(ex));
            paymentRepository.save(row);
            log.info("Charge for payment {} refused while being created: code={}", paymentId, ex.getCode());
            return AtmosCodes.isUnavailable(ex.getCode()) ? StepOutcome.UNAVAILABLE : StepOutcome.REFUSED;
        }
    }

    /** Step 2: the money is taken. A lost or unclear answer leaves the row {@code processing}. */
    @Transactional
    public Payment confirmCommittedCharge(UUID paymentId) {
        Payment row = paymentRepository.findForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!stillAsSeen(row)) {
            return row; // moved by another transaction meanwhile
        }
        if (row.getStatus() != Payment.Status.PROCESSING || row.getProviderTxnId() == null) {
            return row;
        }
        try {
            applyChargeOutcome(row, paymentProvider.confirmCharge(
                    Arrays.asList(row.getProviderTxnId().split(",")), null));
        } catch (ProviderTimeoutException ex) {
            log.warn("Payment {} left processing: confirmation unanswered", paymentId);
        } catch (PaymentProviderException ex) {
            log.warn("Payment {} left processing: confirmation refused, code={}", paymentId, ex.getCode());
        }
        return paymentRepository.save(row);
    }

    /**
     * Before a capture is asked for: the held row becomes {@code capture_pending}, committed,
     * so a capture whose answer is lost is finished by the reconcile job.
     *
     * @return false when the row is not {@code held} (someone else is settling it)
     */
    @Transactional
    public boolean markCapturePending(UUID paymentId) {
        Payment row = paymentRepository.findForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!stillAsSeen(row)) {
            return false; // moved by another transaction meanwhile
        }
        if (row.getStatus() != Payment.Status.HELD) {
            return false;
        }
        row.setStatus(Payment.Status.CAPTURE_PENDING);
        paymentRepository.save(row);
        return true;
    }

    /**
     * Asks for a {@code capture_pending} hold to be captured and records a definite answer:
     * {@code captured}; {@code released} when the hold lapsed or was cancelled; back to
     * {@code held} (with the reason) when the provider refused with any other code — the
     * money is still reserved and whoever undoes the join releases it. No answer, or an
     * unreachable provider, leaves it {@code capture_pending} for the reconcile job.
     */
    @Transactional
    public Payment captureCommitted(UUID paymentId) {
        Payment row = paymentRepository.findForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!stillAsSeen(row)) {
            return row; // moved by another transaction meanwhile
        }
        if (row.getStatus() != Payment.Status.CAPTURE_PENDING) {
            return row;
        }
        try {
            HoldOutcome outcome = paymentProvider.captureHold(row.getProviderTxnId());
            row.setStatus(Payment.Status.CAPTURED);
            if (outcome.commission() != null) {
                row.setProviderCommission(outcome.commission());
            }
            log.info("Hold captured: paymentId={} amount={}", row.getId(), row.getAmount());
        } catch (HoldCancelledException | HoldExpiredException ex) {
            row.setStatus(Payment.Status.RELEASED);
            row.setFailureReason(failureReason(ex));
            log.error("Hold of payment {} could not be captured: {}", row.getId(), ex.getCode());
        } catch (ProviderTimeoutException ex) {
            log.warn("Capture of payment {} unanswered; left capture_pending", row.getId());
        } catch (PaymentProviderException ex) {
            if (AtmosCodes.isUnavailable(ex.getCode())) {
                log.warn("Capture of payment {} not answered (provider unavailable, {}); left capture_pending",
                        row.getId(), ex.getCode());
            } else {
                row.setStatus(Payment.Status.HELD);
                row.setFailureReason(failureReason(ex));
                log.error("Capture of payment {} refused: {}", row.getId(), ex.getCode());
            }
        }
        return paymentRepository.save(row);
    }

    /**
     * The intent to give a payment back, committed before the provider is asked: a settled
     * or held row becomes {@code return_pending}, which {@link #completeReturn} (or, after a
     * crash, the reconcile job) carries out.
     *
     * @return true when there is something to give back
     */
    @Transactional
    public boolean markReturnPending(UUID paymentId) {
        Payment row = paymentRepository.findForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!stillAsSeen(row)) {
            return false; // moved by another transaction meanwhile
        }
        if (row.getAmount() == null || row.getAmount() <= 0 || row.getMethod() == Payment.Method.CASH) {
            return false;
        }
        boolean returnable = row.getStatus() == Payment.Status.HELD
                || row.getStatus() == Payment.Status.CAPTURE_PENDING
                || REFUNDABLE_STATUSES.contains(row.getStatus());
        if (!returnable) {
            return row.getStatus() == Payment.Status.RETURN_PENDING;
        }
        row.setStatus(Payment.Status.RETURN_PENDING);
        paymentRepository.save(row);
        return true;
    }

    /**
     * Carries out a {@code return_pending}: a hold still held is released, money that was
     * taken is reversed in full (a reverse the provider declines becomes a failed refund for
     * an admin). Shared with the reconcile job, so a return is finished the same way whoever
     * gets there.
     */
    @Transactional
    public void completeReturn(UUID paymentId, String reason) {
        Payment row = paymentRepository.findForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!stillAsSeen(row)) {
            return; // moved by another transaction meanwhile
        }
        if (row.getStatus() != Payment.Status.RETURN_PENDING) {
            return; // done meanwhile
        }
        if (row.getHoldExpiresAt() != null) {
            HoldOutcome where = paymentProvider.getHold(row.getProviderTxnId());
            if (where.status() == ProviderStatus.CAPTURED) {
                reverseOrRecordFailure(row, Payment.Status.CAPTURED, reason);
                return;
            }
            if (where.status() == ProviderStatus.HELD) {
                try {
                    paymentProvider.releaseHold(row.getProviderTxnId());
                } catch (HoldCancelledException | HoldExpiredException ex) {
                    // already let go
                }
            }
            row.setStatus(Payment.Status.RELEASED);
            paymentRepository.save(row);
            auditService.record(AuditEntry.system(AuditAction.PAYMENT_RELEASED)
                    .entity(AuditEntityType.PAYMENT, row.getId(), AuditLabels.payment(row))
                    .change("status", Payment.Status.RETURN_PENDING, row.getStatus())
                    .created("releasedAmount", row.getAmount())
                    .reason(reason));
            log.info("Return of hold {}: released", row.getId());
            return;
        }
        reverseOrRecordFailure(row, Payment.Status.PAID, reason);
        log.info("Return of charge {}: {}", row.getId(), row.getStatus().getValue());
    }

    private void reverseOrRecordFailure(Payment row, Payment.Status statusBefore, String reason) {
        try {
            reverseInFull(row, reason);
        } catch (PaymentProviderException ex) {
            recordFailedRefund(row, statusBefore, reason, ex);
            return;
        }
        recordRefund(row, statusBefore, reason);
    }

    /**
     * Locks the payment's row (until this transaction ends) and checks that its committed
     * status is still the one this copy shows. False means another transaction moved it
     * meanwhile — refunded it, started giving it back, captured it — and nothing here may act
     * on the stale copy: above all, never ask the provider to reverse it a second time.
     * Every give-back, capture and return goes through this before calling the provider.
     */
    boolean stillAsSeen(Payment payment) {
        if (payment.getId() == null) {
            return true; // not saved yet: nobody else can have touched it
        }
        String committed = paymentRepository.lockStatus(payment.getId());
        if (committed == null || Payment.Status.fromValue(committed) == payment.getStatus()) {
            return true;
        }
        log.info("Payment {} is {} by now, not {}: left to whoever moved it",
                payment.getId(), committed, payment.getStatus().getValue());
        return false;
    }

    /** The provider splits of a row: the owner's to the owner's recipient, the platform's to the platform. */
    private List<Split> splitsOf(Payment row) {
        List<Split> splits = new ArrayList<>(2);
        if (row.getOwnerAmount() != null && row.getOwnerAmount() > 0) {
            splits.add(Split.owner(recipientIdOf(ownerOf(row.getGameTeam())), row.getOwnerAmount()));
        }
        if (row.getPlatformAmount() != null && row.getPlatformAmount() > 0) {
            splits.add(Split.platform(row.getPlatformAmount()));
        }
        return splits;
    }

    /** What players pay for this owner price: a quote. */
    public long cardPrice(long net) {
        return cardPricing.playerAmount(net);
    }

    /**
     * What a team's card is charged (or held) on joining: {@code playerAmount(advance + fee)};
     * the platform's split nets exactly the fee after ATMOS, the rest is the owner's.
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
        held.setStatus(Payment.Status.CAPTURE_PENDING);
        paymentRepository.save(held);
        HoldOutcome outcome;
        try {
            outcome = paymentProvider.captureHold(held.getProviderTxnId());
        } catch (HoldCancelledException | HoldExpiredException ex) {
            // Nothing is reserved any more: the money was never taken.
            held.setStatus(Payment.Status.RELEASED);
            paymentRepository.save(held);
            log.error("Hold of payment {} could not be captured: {}", held.getId(), ex.getCode());
            throw ex;
        }
        held.setStatus(Payment.Status.CAPTURED);
        if (outcome.commission() != null) {
            held.setProviderCommission(outcome.commission());
        }
        Payment saved = paymentRepository.save(held);
        log.info("Hold captured: paymentId={} amount={}", held.getId(), held.getAmount());
        return saved;
    }

    /**
     * {@link #captureHeld} that reports instead of throwing: false when the hold had lapsed
     * or been let go (row RELEASED), or when the answer was lost (row CAPTURE_PENDING, for
     * the reconcile job). Never throws for those, so a caller can undo its own steps and
     * commit rather than roll back over money that already moved.
     */
    boolean tryCapture(Payment held) {
        try {
            captureHeld(held);
            return true;
        } catch (HoldCancelledException | HoldExpiredException ex) {
            return false;
        } catch (ProviderTimeoutException ex) {
            log.warn("Capture of payment {} unanswered; left capture_pending", held.getId());
            return false;
        }
    }

    /**
     * The owner took one team's remainder in cash: the same amount the team would pay
     * in the app, {@code playerAmount(remainder)}, all of it the owner's. Cash never
     * passes through the provider and is never refunded through the platform — the
     * owner settles that directly.
     */
    @Transactional
    public Payment recordCashTeamRemainder(GameTeam gameTeam, User owner) {
        long amount = cardPricing.teamRemainderPrice(gameTeam.getRemainderAmount());
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

    /** Every payment one team made on one game. */
    @Transactional(readOnly = true)
    public List<Payment> teamPayments(UUID gameTeamId) {
        return paymentRepository.findByGameTeamId(gameTeamId);
    }

    /** One payment as it stands now. */
    @Transactional(readOnly = true)
    public Payment teamPaymentById(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    /**
     * Settles every hold one team placed on one game.
     *
     * @return false when any hold could not be taken (lapsed, let go, or unanswered) —
     *         never an exception, so the joining flow can make everyone whole and commit
     */
    @Transactional
    public boolean captureAllHeldTeamPayments(UUID gameTeamId) {
        boolean all = true;
        for (Payment payment : paymentRepository.findByGameTeamId(gameTeamId)) {
            if (payment.getStatus() == Payment.Status.HELD) {
                all &= tryCapture(payment);
            }
        }
        return all;
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
        if (!stillAsSeen(original)) {
            return null; // another transaction is giving it back, or already has
        }
        if (original.getStatus() == Payment.Status.CAPTURE_PENDING) {
            // Whether the capture went through is unknown; giving back is now the intent, and
            // the reconcile job reverses it if it was taken or releases it if it was not.
            original.setStatus(Payment.Status.RETURN_PENDING);
            paymentRepository.save(original);
            return null;
        }
        if (original.getStatus() == Payment.Status.HELD) {
            original.setStatus(Payment.Status.RETURN_PENDING);
            paymentRepository.save(original);
            try {
                paymentProvider.releaseHold(original.getProviderTxnId());
            } catch (ProviderTimeoutException ex) {
                log.warn("Release of payment {} unanswered; left return_pending", original.getId());
                return null;
            } catch (HoldCancelledException | HoldExpiredException ex) {
                // Already let go — released before, or lapsed at the provider. Either way
                // no money is reserved any more, which is all a release is for, so this
                // counts as success: the timeout job and a retry are idempotent.
                log.info("Hold {} was already let go ({}); recording it released",
                        original.getId(), ex.getCode());
            } catch (PaymentProviderException ex) {
                // Not a failed refund: no money left the card, and the provider lets the hold
                // lapse at the end of its duration anyway. Left return_pending for the
                // reconcile job to release; the cancellation goes ahead.
                log.warn("Release of hold {} refused ({}); left return_pending for the reconcile job",
                        original.getId(), ex.getCode());
                return null;
            }
            Payment.Status statusBefore = Payment.Status.HELD;
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
            if (payment.getStatus() == Payment.Status.HELD && !tryCapture(payment)) {
                // Lapsed or let go: the money promised cannot be taken any more. The rule
                // says it is kept, so this is worth an error, not a silent pass.
                log.error("Payment {} should have been kept but its hold could not be taken: {}",
                        payment.getId(), payment.getStatus().getValue());
            }
            if (KEPT_STATUSES.contains(payment.getStatus()) || payment.getStatus() == Payment.Status.CAPTURE_PENDING) {
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
                .ownerAmount(split.ownerAmount())
                .platformAmount(split.platformAmount())
                .cardFeeExpected(split.atmosFee())
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
        PendingCharge pending = null;
        try {
            pending = paymentProvider.charge(ChargeRequest.withToken(
                    splitsFor(owner, split), card.getProviderToken(), saved.getId().toString()));
            saved.setProviderTxnId(String.join(",", pending.transactionIds()));
            applyChargeOutcome(saved, paymentProvider.confirmCharge(pending.transactionIds(), null));
        } catch (ProviderTimeoutException ex) {
            log.warn("Payment {} left processing: {}", saved.getId(), ex.getMessage());
        } catch (PaymentProviderException ex) {
            refusedByProvider(saved, pending != null, ex);
        }
        return paymentRepository.save(saved);
    }

    static final String PROVIDER_UNAVAILABLE =
            "To'lov tizimi vaqtincha ishlamayapti. Birozdan so'ng qayta urinib ko'ring";

    /**
     * The provider refused a charge or hold outright (not a decline, which is an outcome).
     * Refused while being created: nothing exists at the provider, so an unreachable provider
     * is a 502 the player may retry (this transaction, the row included, rolls back) and any
     * other refusal (a removed card, an amount under the minimum) a failed row. Refused once
     * created: whether the money moved is unknown, so the row stays {@code processing} for
     * the reconcile job, like a lost answer.
     */
    private void refusedByProvider(Payment payment, boolean created, PaymentProviderException ex) {
        if (created) {
            log.warn("Payment {} left processing: provider refused the confirmation, code={}",
                    payment.getId(), ex.getCode());
            return;
        }
        if (AtmosCodes.isUnavailable(ex.getCode())) {
            log.warn("Payment {} not attempted: provider unavailable, code={}", payment.getId(), ex.getCode());
            throw new ProviderUnavailableException(PROVIDER_UNAVAILABLE);
        }
        payment.setStatus(Payment.Status.FAILED);
        payment.setFailureReason(failureReason(ex));
        log.info("Payment {} refused by the provider: code={}", payment.getId(), ex.getCode());
    }

    /** As {@link #chargeCard}, for a hold of {@code minutes}. */
    private Payment holdCard(Payment row, SavedCard card, CardFeeSplit split, User owner, int minutes) {
        // Set before the call, so even a hold whose creation went unanswered is known to be a hold.
        row.setHoldExpiresAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(minutes));
        Payment saved = paymentRepository.save(row);
        HoldOutcome created = null;
        try {
            created = paymentProvider.hold(new HoldRequest(
                    splitsFor(owner, split), card.getProviderToken(), saved.getId().toString(), minutes));
            saved.setProviderTxnId(created.holdId());
            saved.setHoldExpiresAt(created.holdTill());
            applyHoldOutcome(saved, paymentProvider.confirmHold(created.holdId(), null));
        } catch (ProviderTimeoutException ex) {
            log.warn("Payment {} left processing: {}", saved.getId(), ex.getMessage());
        } catch (PaymentProviderException ex) {
            refusedByProvider(saved, created != null, ex);
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
    void reverseInFull(Payment original, String reason) {
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
        if (split.ownerAmount() > 0) {
            splits.add(Split.owner(recipientIdOf(owner), split.ownerAmount()));
        }
        if (split.platformAmount() > 0) {
            splits.add(Split.platform(split.platformAmount()));
        }
        return splits;
    }

    /**
     * The ATMOS recipient an owner's split is paid to — stored only on the owner's legal
     * details, and only once the contract is signed. Null when there is none.
     */
    private String recipientIdOf(User owner) {
        return owner == null ? null : ownerLegalDetailsRepository.findById(owner.getId())
                .map(OwnerLegalDetails::getAtmosRecipientId)
                .orElse(null);
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

        // Under the player price the booking counts what the player paid, against D; an
        // older booking counts what the payment is worth to the owner, against their price.
        long credit = booking.creditOf(payment);
        long paidBefore = booking.getPaidAmount() == null ? 0L : booking.getPaidAmount();
        long paid = paidBefore + credit;
        String previousStatus = booking.getStatus();
        Booking.PaymentStatus paymentStatusBefore = booking.getPaymentStatus();
        booking.setPaidAmount(paid);
        booking.setPaymentStatus(paid >= booking.totalDue()
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
            booking.setPaidAmount(paid - credit);
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
     * <p>A provider that declines the reverse does not undo whatever is being cancelled:
     * the refund is recorded as failed ({@link #recordFailedRefund}) for an admin to retry
     * or settle by hand, and the caller carries on. A lost answer is different — the row
     * stays {@code return_pending} for the reconcile job.
     *
     * @return the new negative refund row, or {@code null} if there was nothing
     *         to refund, the answer was lost, or the provider declined
     */
    @Transactional
    public Payment refundInFull(Payment original, String reason) {
        if (original.getMethod() == Payment.Method.CASH) {
            log.info("Cash payment {} is not refunded through the platform", original.getId());
            return null;
        }
        // Locked and read as committed: a payment refunded (or being refunded) by another
        // transaction since this one loaded it is never sent to the provider a second time.
        if (!stillAsSeen(original) || !REFUNDABLE_STATUSES.contains(original.getStatus())) {
            return null; // never settled, or already given back
        }
        if (original.getAmount() == null || original.getAmount() <= 0) {
            return null; // a refund row itself is not refundable
        }

        // Only the card provider can give money back. Legacy Payme/Click rows
        // predate it and are reversed on paper only, as they always were.
        Payment.Status statusBefore = original.getStatus();
        if (original.getProvider() == Payment.Provider.ATMOS) {
            original.setStatus(Payment.Status.RETURN_PENDING);
            paymentRepository.save(original);
            try {
                reverseInFull(original, reason);
            } catch (ProviderTimeoutException ex) {
                log.warn("Reverse of payment {} unanswered; left return_pending", original.getId());
                return null;
            } catch (PaymentProviderException ex) {
                recordFailedRefund(original, statusBefore, reason, ex);
                return null;
            }
        }
        return recordRefund(original, statusBefore, reason);
    }

    /**
     * The provider has given the whole gross back: the negative refund row, and the
     * original marked refunded. Also used by the reconcile job to finish a return_pending.
     */
    Payment recordRefund(Payment original, Payment.Status statusBefore, String reason) {
        Payment refund = paymentRepository.save(refundRow(original, reason)
                .status(Payment.Status.REFUNDED)
                .build());

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

    /**
     * The provider declined to give the money back. The refund row is written all the
     * same, {@code failed}, with the provider's error, and the original becomes
     * {@code refund_failed}: the money is owed and an admin has to act — retry it, or
     * mark it refunded by hand. Neither status is one the reconcile job picks up, so the
     * job never races an admin over it. Also used by the reconcile job when its own
     * reverse is declined.
     */
    Payment recordFailedRefund(Payment original, Payment.Status statusBefore, String reason,
                               PaymentProviderException cause) {
        String failure = failureReason(cause);
        Payment refund = paymentRepository.save(refundRow(original, reason)
                .status(Payment.Status.FAILED)
                .failureReason(failure)
                .build());

        original.setStatus(Payment.Status.REFUND_FAILED);
        paymentRepository.save(original);

        auditService.record(AuditEntry.system(AuditAction.REFUND_FAILED)
                .entity(AuditEntityType.PAYMENT, original.getId(), AuditLabels.payment(original))
                .change("status", statusBefore, original.getStatus())
                .created("refundAmount", refund.getAmount())
                .created("refundPaymentId", refund.getId())
                .reason(failure));
        log.error("Refund declined by the provider, left for an admin: originalId={} refundId={} code={}",
                original.getId(), refund.getId(), cause.getCode());
        return refund;
    }

    /** What an admin is shown of a provider error: its message and code. Never a card detail. */
    static String failureReason(PaymentProviderException cause) {
        String message = cause.getMessage() == null || cause.getMessage().isBlank()
                ? "Refund declined by the provider" : cause.getMessage();
        String text = cause.getCode() == null || message.contains(cause.getCode())
                ? message : message + " (" + cause.getCode() + ")";
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** The negative mirror of a payment — the whole gross, card fee included — status still to set. */
    private Payment.PaymentBuilder refundRow(Payment original, String reason) {
        return Payment.builder()
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
                .refundReason(reason)
                .refundOfPayment(original);
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

    /**
     * A casual player's share charged to a saved card. On success the game hears of it as
     * it does from the legacy webhook; a lost answer leaves the row processing for the
     * reconcile job.
     */
    @Transactional
    public Payment chargeGameShare(UUID gameId, UUID userId, SavedCard card) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found"));
        if ("team_vs_team".equals(game.getType())) {
            throw new BadRequestException("A team game is paid when creating or joining it");
        }
        GameParticipant participant = gameParticipantRepository.findByGameIdAndUserId(gameId, userId)
                .orElseThrow(() -> new BadRequestException("Join the game before paying"));
        if (participant.getStatus() == GameParticipant.Status.PAID) {
            throw new BadRequestException("Already paid");
        }
        CardFeeSplit share = gameShareSplit(game);
        Payment row = Payment.builder()
                .game(game)
                .user(participant.getUser())
                .amount(share.playerAmount())
                .ownerAmount(share.ownerAmount())
                .platformAmount(share.platformAmount())
                .cardFeeExpected(share.atmosFee())
                .commissionPercent(0)
                .method(Payment.Method.CARD)
                .paymentType(Payment.PaymentType.GAME_SHARE)
                .provider(Payment.Provider.ATMOS)
                .status(Payment.Status.PROCESSING)
                .build();
        Payment payment = chargeCard(row, card, share, game.getStadium() == null ? null : game.getStadium().getOwner());
        participant.setPayment(payment);
        gameParticipantRepository.save(participant);
        if (payment.getStatus() == Payment.Status.PAID) {
            auditPaymentCompleted(payment, null);
            gameService.handleGamePaymentConfirmed(gameId, userId);
        }
        log.info("Game share by card {}: gameId={} paymentId={} amount={}",
                payment.getStatus().getValue(), gameId, payment.getId(), payment.getAmount());
        return payment;
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
                .ownerAmount(share.ownerAmount())
                .platformAmount(share.platformAmount())
                .cardFeeExpected(share.atmosFee())
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
    void auditPaymentCompleted(Payment payment, Payment.Status statusBefore) {
        auditService.record(AuditEntry.system(AuditAction.PAYMENT_COMPLETED)
                .entity(AuditEntityType.PAYMENT, payment.getId(), AuditLabels.payment(payment))
                .change("status", statusBefore, payment.getStatus())
                .created("amount", payment.getAmount())
                .created("method", payment.getMethod())
                .created("provider", payment.getProvider()));
    }

    /** Every payment and refund row of one booking. */
    @Transactional(readOnly = true)
    public List<Payment> paymentsOf(UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId);
    }

    @Transactional(readOnly = true)
    public List<Payment> refundableCardPayments(UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId).stream()
                .filter(PaymentService::isRefundableCardPayment)
                .toList();
    }

    /**
     * Whether a cancellation would give this payment back: card money that arrived and
     * has not been returned. Also how a list of bookings works out each one's
     * {@code refundIfCancelledNow} from payments it has already loaded.
     */
    public static boolean isRefundableCardPayment(Payment payment) {
        return payment.getMethod() != Payment.Method.CASH
                && REFUNDABLE_STATUSES.contains(payment.getStatus())
                && payment.getAmount() != null && payment.getAmount() > 0;
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
            } else if (original.getStatus() == Payment.Status.RETURN_PENDING) {
                refunded += original.getAmount(); // on its way back; the reconcile job finishes it
            }
        }
        if (refunded == 0) {
            log.info("Nothing to refund for booking {}", bookingId);
        }
        return refunded;
    }

    /**
     * Whether a card payment of this booking is still being settled with the provider — its
     * outcome unknown, so money may yet land. The owner must not record the same money in
     * cash meanwhile.
     */
    @Transactional(readOnly = true)
    public boolean hasPaymentInFlight(UUID bookingId) {
        return paymentRepository.existsByBookingIdAndStatusIn(bookingId,
                java.util.List.of(Payment.Status.PROCESSING, Payment.Status.CAPTURE_PENDING));
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
        // The same remainder the player would pay in the app: D minus what was paid.
        long amount = booking.getRemainingAmount() > 0 ? booking.getRemainingAmount() : booking.totalDue();
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
