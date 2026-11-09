package uz.minipolya.minipolya_backend.util;

/**
 * What each side keeps of one payment row, tiyin, negative on a refund. Worked out from the
 * row's stored amounts, which never change:
 * {@code ownerNet + platformNet + atmosFee == amount}.
 *
 * @param ownerNet    the owner's, after ATMOS; the rounding remainder is the owner's
 * @param platformNet the platform's, after ATMOS: exactly the fee on a team payment
 * @param atmosFee    what ATMOS is expected to take
 */
public record PaymentNets(long ownerNet, long platformNet, long atmosFee) {

    /** @param storedFee the row's {@code card_fee_expected}; null = its rate on each split */
    public static PaymentNets ofCard(long amount, long ownerAmount, long platformAmount, Long storedFee, int cardFeeBps) {
        long platformNet = net(platformAmount, cardFeeBps);
        long fee = storedFee != null ? storedFee : amount - net(ownerAmount, cardFeeBps) - platformNet;
        return new PaymentNets(amount - platformNet - fee, platformNet, fee);
    }

    /** Cash goes to the owner in full. */
    public static PaymentNets ofCash(long amount) {
        return new PaymentNets(amount, 0L, 0L);
    }

    /** A refund mirrors its payment, so it is rounded the same way. */
    private static long net(long gross, int cardFeeBps) {
        return Long.signum(gross) * PriceCalculator.netAfterFee(Math.abs(gross), cardFeeBps);
    }
}
