package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;

import java.util.Locale;
import java.util.UUID;

/**
 * F6: an admin settles a hold left {@code needs_review} — one whose capture or give-back had
 * no clear answer from ATMOS — once they have found out at ATMOS what really happened. No
 * provider call is made here: this records the truth, it does not move money.
 *
 * <ul>
 *   <li>{@code captured}: the money was taken and is kept. A team join waiting on it is
 *       confirmed by the reconcile job's next run.</li>
 *   <li>{@code released}: nothing was taken. A join waiting on it is undone the same way.</li>
 *   <li>{@code refunded}: it was taken and has since gone back — the refund row is written,
 *       marked as returned by hand, exactly like a failed refund marked refunded.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReviewService {

    public enum Outcome { CAPTURED, RELEASED, REFUNDED }

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final AuditService auditService;

    @Transactional
    public Payment resolve(UUID paymentId, String outcomeName, String reason, UUID adminId) {
        Outcome outcome = parse(outcomeName);
        String text = RefundRecoveryService.requireReason(reason);
        Payment payment = paymentRepository.findForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (payment.getStatus() != Payment.Status.NEEDS_REVIEW) {
            throw new BadRequestException("Only a payment that needs review can be resolved");
        }
        Payment.Status before = payment.getStatus();
        switch (outcome) {
            case CAPTURED -> payment.setStatus(Payment.Status.CAPTURED);
            case RELEASED -> payment.setStatus(Payment.Status.RELEASED);
            case REFUNDED -> {
                Payment refund = paymentService.recordRefund(payment, before, text);
                refund.setRefundedManually(true);
                refund.setManualRefundReason(text);
                refund.setManualRefundBy(adminId);
                paymentRepository.save(refund);
            }
        }
        paymentRepository.save(payment);
        auditService.record(AuditEntry.admin(adminId, AuditAction.PAYMENT_REVIEW_RESOLVED)
                .entity(AuditEntityType.PAYMENT, payment.getId(), AuditLabels.payment(payment))
                .change("status", before, payment.getStatus())
                .reason(text));
        log.info("Payment {} resolved by admin {}: {}", paymentId, adminId, payment.getStatus().getValue());
        return payment;
    }

    private static Outcome parse(String value) {
        try {
            return Outcome.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("outcome must be one of captured, released, refunded");
        }
    }
}
