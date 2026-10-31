package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.service.PaymentReconcileService;

import java.util.List;
import java.util.UUID;

/**
 * Every five minutes, settles payments stuck in processing, return_pending or
 * capture_pending for more than five minutes, each in its own transaction so one that
 * fails does not hold the others back. Idempotent: a settled row is not picked up again,
 * and repeating a reverse or a release counts "already done" as done.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileJob {

    private final PaymentReconcileService reconcileService;

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
    }
}
