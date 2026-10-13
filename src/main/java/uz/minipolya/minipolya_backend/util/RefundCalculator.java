package uz.minipolya.minipolya_backend.util;

import org.springframework.stereotype.Component;

/**
 * Pure, stateless cancellation-refund math. The policy is configured per
 * stadium (full / partial refund windows and the partial percentage). All
 * money is integer so'm.
 */
@Component
public class RefundCalculator {

    /**
     * @param hoursLeft            whole hours between now and the booking start
     * @param fullRefundHours     at or beyond this, 100% is refunded
     * @param partialRefundHours  at or beyond this (but under full), the partial % applies
     * @param partialRefundPercent the partial refund percentage (0..100)
     * @return the refund percentage: 100, the partial percent, or 0
     */
    public int refundPercent(long hoursLeft, int fullRefundHours, int partialRefundHours, int partialRefundPercent) {
        if (hoursLeft >= fullRefundHours) {
            return 100;
        }
        if (hoursLeft >= partialRefundHours) {
            return partialRefundPercent;
        }
        return 0;
    }

    /** Refund due in so'm, rounding down. Uses long math to avoid overflow. */
    public int refundAmount(int price, int percent) {
        return (int) ((long) price * percent / 100);
    }
}
