package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.HoldCancelledException;
import uz.minipolya.minipolya_backend.provider.HoldExpiredException;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Settles payments stuck mid-step — an answer that never came, or a give-back or capture
 * that did not finish — by asking the provider where they stand and moving the row on.
 * Idempotent: a row already settled is no longer picked up, and every provider step it
 * repeats (reverse, release) treats "already done" as done.
 *
 * <ul>
 *   <li><b>processing</b> charge: getCharge → paid (the booking is confirmed, the game told,
 *       the remainder settled — or, when what it paid for has gone, given straight back)
 *       or failed. Never confirmed after {@link #ABANDONED_AFTER}: no money moved, failed.</li>
 *   <li><b>processing</b> hold: getHold → held (released at once if its game has ended) or
 *       failed (and the waiting game it was for ends).</li>
 *   <li><b>return_pending</b>: reversed if the money was taken, released if it is held.</li>
 *   <li><b>capture_pending</b>: captured if still held; released (with an error) if gone.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconcileService {

    /** A row must have been stuck this long before the job touches it. */
    static final Duration STUCK_AFTER = Duration.ofMinutes(5);
    /** An unconfirmed charge or hold this old was abandoned: the provider never took the money. */
    static final Duration ABANDONED_AFTER = Duration.ofMinutes(30);
    static final EnumSet<Payment.Status> STUCK = EnumSet.of(
            Payment.Status.PROCESSING, Payment.Status.RETURN_PENDING, Payment.Status.CAPTURE_PENDING);

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final PaymentService paymentService;
    private final TeamGameService teamGameService;
    private final GameService gameService;
    private final Clock clock;

    /** The rows due for a look now. */
    @Transactional(readOnly = true)
    public List<UUID> due() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(STUCK_AFTER);
        return paymentRepository.findByStatusInAndUpdatedAtBefore(STUCK, cutoff).stream()
                .map(Payment::getId)
                .toList();
    }

    /** Settles one row, in its own transaction. */
    @Transactional
    public void reconcile(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || !STUCK.contains(payment.getStatus())) {
            return; // settled meanwhile
        }
        switch (payment.getStatus()) {
            case PROCESSING -> {
                if (isHold(payment)) {
                    reconcileHold(payment);
                } else {
                    reconcileCharge(payment);
                }
            }
            case RETURN_PENDING -> finishReturn(payment);
            case CAPTURE_PENDING -> finishCapture(payment);
            default -> {
                // not stuck
            }
        }
        paymentRepository.save(payment);
    }

    // ---- processing ----

    private void reconcileCharge(Payment payment) {
        List<String> ids = idsOf(payment.getProviderTxnId());
        if (ids.isEmpty()) {
            // The provider never acknowledged creating it, so it never took the money.
            payment.setStatus(Payment.Status.FAILED);
            log.info("Reconciled payment {}: never created at the provider, failed", payment.getId());
            return;
        }
        List<String> successIds = new ArrayList<>();
        Long commission = null;
        String ofdUrl = null;
        ProviderStatus overall = ProviderStatus.SUCCESS;
        for (String id : ids) {
            ChargeOutcome one = paymentProvider.getCharge(id);
            if (one.status() == ProviderStatus.FAILED) {
                overall = ProviderStatus.FAILED;
                break;
            }
            if (one.status() != ProviderStatus.SUCCESS) {
                overall = ProviderStatus.PENDING;
                continue;
            }
            successIds.addAll(one.successTransIds());
            if (one.commission() != null) {
                commission = (commission == null ? 0L : commission) + one.commission();
            }
            ofdUrl = ofdUrl == null ? one.ofdUrl() : ofdUrl;
        }
        if (overall == ProviderStatus.PENDING && abandoned(payment)) {
            overall = ProviderStatus.FAILED;
        }
        ChargeOutcome outcome = overall == ProviderStatus.SUCCESS
                ? new ChargeOutcome(ProviderStatus.SUCCESS, successIds, commission, ofdUrl, null)
                : overall == ProviderStatus.FAILED ? ChargeOutcome.failed("RECONCILED") : ChargeOutcome.pending();
        paymentService.applyChargeOutcome(payment, outcome);
        log.info("Reconciled charge {}: {}", payment.getId(), payment.getStatus().getValue());
        if (payment.getStatus() == Payment.Status.PAID) {
            paymentRepository.save(payment);
            onPaid(payment);
        }
    }

    /** The money arrived after all: finish what it was for, or give it back if that is gone. */
    private void onPaid(Payment payment) {
        paymentService.auditPaymentCompleted(payment, Payment.Status.PROCESSING);
        if (payment.getBooking() != null) {
            // The one path from money to a confirmed booking; it refunds if the slot has gone.
            paymentService.applySuccessfulBookingPayment(payment.getBooking(), payment);
            return;
        }
        GameTeam team = payment.getGameTeam();
        if (team != null) {
            if (team.getStatus() == GameTeam.Status.ACTIVE && payment.getPaymentType() == Payment.PaymentType.REMAINDER) {
                teamGameService.settleRemainderPaidLate(team);
            } else {
                // A join that was abandoned when this charge went unanswered: the team left.
                paymentService.giveBackInFull(payment, "The game could not be joined; payment returned");
            }
            return;
        }
        Game game = payment.getGame();
        if (game != null && payment.getPaymentType() == Payment.PaymentType.GAME_SHARE) {
            if ("open".equals(game.getStatus())) {
                gameService.handleGamePaymentConfirmed(game.getId(), payment.getUser().getId());
            } else {
                paymentService.giveBackInFull(payment, "The game was no longer open; payment returned");
            }
        }
    }

    private void reconcileHold(Payment payment) {
        String holdId = payment.getProviderTxnId();
        if (holdId == null) {
            payment.setStatus(Payment.Status.FAILED);
            onHoldFailed(payment);
            return;
        }
        HoldOutcome outcome = paymentProvider.getHold(holdId);
        if (outcome.status() == ProviderStatus.PENDING && abandoned(payment)) {
            outcome = new HoldOutcome(holdId, ProviderStatus.FAILED, null, null, "RECONCILED");
        }
        if (outcome.status() == ProviderStatus.RELEASED) {
            outcome = new HoldOutcome(holdId, ProviderStatus.FAILED, null, null, "RECONCILED");
        }
        paymentService.applyHoldOutcome(payment, outcome);
        log.info("Reconciled hold {}: {}", payment.getId(), payment.getStatus().getValue());
        if (payment.getStatus() == Payment.Status.FAILED) {
            onHoldFailed(payment);
        } else if (payment.getStatus() == Payment.Status.HELD && !stillWanted(payment)) {
            paymentRepository.save(payment);
            paymentService.giveBackInFull(payment, "The game ended before the hold was confirmed");
        }
    }

    /** The creating team's hold never went through: the game it was for cannot go on. */
    private void onHoldFailed(Payment payment) {
        GameTeam team = payment.getGameTeam();
        if (team != null && team.getStatus() == GameTeam.Status.ACTIVE && team.getGame() != null
                && "open".equals(team.getGame().getStatus())) {
            paymentRepository.save(payment);
            teamGameService.endWaitingGame(team.getGame(), "cancelled_not_filled",
                    "The creating team's payment did not go through");
        }
    }

    private boolean stillWanted(Payment payment) {
        GameTeam team = payment.getGameTeam();
        return team != null && team.getStatus() == GameTeam.Status.ACTIVE && team.getGame() != null
                && "open".equals(team.getGame().getStatus());
    }

    // ---- return_pending / capture_pending ----

    private void finishReturn(Payment payment) {
        if (isHold(payment)) {
            HoldOutcome where = paymentProvider.getHold(payment.getProviderTxnId());
            if (where.status() == ProviderStatus.CAPTURED) {
                paymentService.reverseInFull(payment, "Returned by reconciliation");
                paymentService.recordRefund(payment, Payment.Status.CAPTURED, "Returned by reconciliation");
                return;
            }
            if (where.status() == ProviderStatus.HELD) {
                try {
                    paymentProvider.releaseHold(payment.getProviderTxnId());
                } catch (HoldCancelledException | HoldExpiredException ex) {
                    // already let go
                }
            }
            payment.setStatus(Payment.Status.RELEASED);
            log.info("Reconciled return of hold {}: released", payment.getId());
            return;
        }
        paymentService.reverseInFull(payment, "Returned by reconciliation");
        paymentService.recordRefund(payment, Payment.Status.PAID, "Returned by reconciliation");
        log.info("Reconciled return of charge {}: refunded", payment.getId());
    }

    private void finishCapture(Payment payment) {
        HoldOutcome where = paymentProvider.getHold(payment.getProviderTxnId());
        if (where.status() == ProviderStatus.CAPTURED) {
            payment.setStatus(Payment.Status.CAPTURED);
            return;
        }
        if (where.status() == ProviderStatus.HELD) {
            try {
                HoldOutcome captured = paymentProvider.captureHold(payment.getProviderTxnId());
                payment.setStatus(Payment.Status.CAPTURED);
                payment.setProviderCommission(captured.commission());
                return;
            } catch (HoldCancelledException | HoldExpiredException ex) {
                // fell through below
            }
        }
        payment.setStatus(Payment.Status.RELEASED);
        log.error("Payment {} was to be kept but its hold could no longer be taken", payment.getId());
    }

    // ---- helpers ----

    private static boolean isHold(Payment payment) {
        return payment.getHoldExpiresAt() != null;
    }

    private boolean abandoned(Payment payment) {
        OffsetDateTime since = payment.getCreatedAt() == null ? payment.getUpdatedAt() : payment.getCreatedAt();
        return since != null && since.isBefore(OffsetDateTime.now(clock).minus(ABANDONED_AFTER));
    }

    private static List<String> idsOf(String joined) {
        return joined == null || joined.isBlank() ? List.of() : Arrays.asList(joined.split(","));
    }
}
