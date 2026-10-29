package uz.minipolya.minipolya_backend.util;

import java.util.Collection;

/**
 * Card money the platform took in over a period, and where it went. Every figure is tiyin.
 *
 * <ul>
 *   <li>{@code appRevenue} — card money kept: the gross of every card payment that
 *       arrived ({@code paid}, {@code captured}, {@code completed}, or since given back),
 *       less every card refund completed in the period. Cash is never in it.</li>
 *   <li>{@code cardFees} — what ATMOS took of that money.</li>
 *   <li>{@code ownerPayouts} — what the owners actually receive: their splits less ATMOS's
 *       fee on them.</li>
 *   <li>{@code platformRevenue} — what the platform actually keeps: its splits less ATMOS's
 *       fee on them.</li>
 * </ul>
 *
 * {@code ownerPayouts + platformRevenue + cardFees == appRevenue} always: it holds for
 * every single payment ({@link Line}), so it holds for any sum of them.
 */
public record Revenue(long appRevenue, long cardFees, long ownerPayouts, long platformRevenue) {

    public static final Revenue ZERO = new Revenue(0, 0, 0, 0);

    /**
     * One payment's part in the figures. {@code fee} is what ATMOS took of it (negative
     * on a refund that gave the fee back too). ATMOS takes the same rate from every split,
     * so the owner's share of the fee is proportional to the owner's split; the platform's
     * share is the rest, so the line adds up exactly.
     */
    public static Revenue line(long amount, long ownerAmount, long platformAmount, long fee) {
        long ownerFee = amount == 0 ? 0 : Math.multiplyExact(fee, ownerAmount) / amount;
        long platformFee = fee - ownerFee;
        return new Revenue(amount, fee, ownerAmount - ownerFee, platformAmount - platformFee);
    }

    public Revenue plus(Revenue other) {
        return new Revenue(appRevenue + other.appRevenue, cardFees + other.cardFees,
                ownerPayouts + other.ownerPayouts, platformRevenue + other.platformRevenue);
    }

    public static Revenue sum(Collection<Revenue> lines) {
        return lines.stream().reduce(ZERO, Revenue::plus);
    }
}
