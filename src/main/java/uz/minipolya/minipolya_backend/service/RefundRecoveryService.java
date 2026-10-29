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
import uz.minipolya.minipolya_backend.exception.ProviderUnavailableException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;

import java.util.UUID;

/**
 * The two ways an admin settles a refund the provider declined (see
 * {@code PaymentService.recordFailedRefund}): try the provider again, or record that the
 * money went back outside the platform.
 *
 * <p>Both act on the existing {@code failed} refund row, locked for the duration, and
 * never write a second one. A retry reverses the same transactions the first attempt
 * did — reversing one that already went back is answered "closed" and counts as done —
 * so however often it is retried, the money goes back once. Failed refunds are only ever
 * retried here: the reconcile job does not pick up {@code failed} or {@code refund_failed}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundRecoveryService {

    private static final int MIN_REASON = 10;
    private static final int MAX_REASON = 500;

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final AuditService auditService;

    /**
     * Calls the provider again. A decline is a normal answer: the refund stays
     * {@code failed} with the new reason. Only a provider that cannot be reached at all
     * is an error (502), and then nothing changes.
     */
    @Transactional
    public Payment retry(UUID refundId, UUID adminId) {
        Payment refund = lockFailedRefund(refundId, "Only a failed refund can be retried");
        Payment original = refund.getRefundOfPayment();
        String failureBefore = refund.getFailureReason();
        try {
            paymentService.reverseInFull(original, refund.getRefundReason());
        } catch (ProviderTimeoutException ex) {
            log.warn("Refund retry of {} unanswered; left failed for another try", refundId);
            throw new ProviderUnavailableException("Payment provider unavailable");
        } catch (PaymentProviderException ex) {
            refund.setFailureReason(PaymentService.failureReason(ex));
            paymentRepository.save(refund);
            auditService.record(AuditEntry.admin(adminId, AuditAction.REFUND_RETRIED)
                    .entity(AuditEntityType.PAYMENT, refund.getId(), AuditLabels.payment(refund))
                    .change("failureReason", failureBefore, refund.getFailureReason())
                    .reason(refund.getFailureReason()));
            log.warn("Refund retry of {} declined again: code={}", refundId, ex.getCode());
            return refund;
        }

        refund.setStatus(Payment.Status.REFUNDED);
        refund.setFailureReason(null);
        paymentRepository.save(refund);
        original.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(original);
        auditService.record(AuditEntry.admin(adminId, AuditAction.REFUND_RETRIED)
                .entity(AuditEntityType.PAYMENT, refund.getId(), AuditLabels.payment(refund))
                .change("status", Payment.Status.FAILED, refund.getStatus())
                .change("failureReason", failureBefore, null)
                .change("originalStatus", Payment.Status.REFUND_FAILED, original.getStatus()));
        log.info("Refund retried and completed: refundId={} originalId={} amount={}",
                refundId, original.getId(), refund.getAmount());
        return refund;
    }

    /** The money went back outside the platform (cash, a bank transfer): recorded, no provider call. */
    @Transactional
    public Payment markRefunded(UUID refundId, String reason, UUID adminId) {
        String text = requireReason(reason);
        Payment refund = lockFailedRefund(refundId, "Only a failed refund can be marked as refunded");
        Payment original = refund.getRefundOfPayment();

        refund.setStatus(Payment.Status.REFUNDED);
        refund.setRefundedManually(true);
        refund.setManualRefundReason(text);
        refund.setManualRefundBy(adminId);
        paymentRepository.save(refund);
        original.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(original);
        auditService.record(AuditEntry.admin(adminId, AuditAction.REFUND_MARKED_MANUAL)
                .entity(AuditEntityType.PAYMENT, refund.getId(), AuditLabels.payment(refund))
                .change("status", Payment.Status.FAILED, refund.getStatus())
                .change("refundedManually", false, true)
                .change("originalStatus", Payment.Status.REFUND_FAILED, original.getStatus())
                .reason(text));
        log.info("Refund marked as returned by hand: refundId={} originalId={} by admin {}",
                refundId, original.getId(), adminId);
        return refund;
    }

    private Payment lockFailedRefund(UUID refundId, String notFailedMessage) {
        Payment refund = paymentRepository.findForUpdate(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (refund.getPaymentType() != Payment.PaymentType.REFUND
                || refund.getStatus() != Payment.Status.FAILED
                || refund.getRefundOfPayment() == null) {
            throw new BadRequestException(notFailedMessage);
        }
        return refund;
    }

    private static String requireReason(String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new BadRequestException("reason is required");
        }
        if (text.length() < MIN_REASON) {
            throw new BadRequestException("reason must be at least 10 characters");
        }
        if (text.length() > MAX_REASON) {
            throw new BadRequestException("reason must be at most 500 characters");
        }
        return text;
    }
}
