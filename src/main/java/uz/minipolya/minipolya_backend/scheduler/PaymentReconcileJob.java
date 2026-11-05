package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.service.GameService;
import uz.minipolya.minipolya_backend.service.PaymentReconcileService;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Every five minutes, settles payments stuck in processing, return_pending or
 * capture_pending for more than five minutes, each in its own transaction so one that
 * fails does not hold the others back. Then carries on every team-game join left half-way
 * for as long (a crash between its steps, or an answer that came late): confirmed once the
 * waiting team's hold is captured, undone with everyone's money back otherwise.
 * Idempotent: a settled row is not picked up again, and repeating a reverse or a release
 * counts "already done" as done.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileJob {

    private final PaymentReconcileService reconcileService;
    private final GameService gameService;
    private final Clock clock;

    @Scheduled(fixedRate = 300_000)
    public void reconcileStuckPayments() {
        List<UUID> due = reconcileService.due();
        int failed = 0;
        for (UUID id : due) {
            try {
                reconcileService.reconcile(id);
            } catch (RuntimeException ex) {
                failed++;
                // Ids and the exception type only: never a body or a card detail.
                log.error("Could not reconcile payment {}: {}", id, ex.getClass().getSimpleName());
            }
        }
        if (!due.isEmpty()) {
            log.info("Reconciled {} stuck payment(s), {} to retry", due.size() - failed, failed);
        }
        // After the payments, so each join sees its rows settled.
        int joins = gameService.resumeStuckJoins(OffsetDateTime.now(clock).minus(PaymentReconcileService.STUCK_AFTER));
        if (joins > 0) {
            log.info("Resumed {} team-game join(s) left half-way", joins);
        }
    }
}
