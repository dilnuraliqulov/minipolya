package uz.minipolya.minipolya_backend.util;

import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;

/**
 * How a team-vs-team game's money divides between the two teams. Every figure
 * is tiyin.
 *
 * <p>Each team pays the platform fee plus an owner advance when it joins in the
 * app, and settles its remainder later. The advance base is the stadium's
 * {@code min_booking_amount}, or the whole pitch price when the stadium has no
 * minimum; the two advances always add up to exactly that base, and the two
 * remainders to exactly what is left of the price. Where a halving is odd, the
 * extra tiyin goes to the second team, so nothing is lost to integer division.
 *
 * <p>The fee is charged on top of the pitch price and is never part of it:
 * {@link #joinAmountFor} is what the card is charged, {@link #advanceFor} is
 * what reaches the booking.
 *
 * <p>Pure and stateless — the one place this arithmetic is expressed, used by
 * the charge itself and by the "what would I get back" preview alike.
 */
public record TeamGameSplit(long fee,
                            long advanceBase,
                            long advanceA,
                            long advanceB,
                            long remainderA,
                            long remainderB) {

    /**
     * @param price             the pitch price for the whole game, tiyin
     * @param minBookingAmount  the stadium minimum, or null when it requires full payment
     * @param fee               the per-team platform fee, tiyin
     */
    public static TeamGameSplit of(long price, Long minBookingAmount, long fee) {
        // A minimum at or above the price leaves no remainder to settle later,
        // so the advance simply covers the whole pitch.
        long base = (minBookingAmount == null || minBookingAmount > price) ? price : minBookingAmount;
        long remainderTotal = price - base;

        long advanceA = base / 2;
        long remainderA = remainderTotal / 2;
        return new TeamGameSplit(fee, base, advanceA, base - advanceA,
                remainderA, remainderTotal - remainderA);
    }

    /** The owner advance this side pays up front — the part that reaches the booking. */
    public long advanceFor(Side side) {
        return side == Side.B ? advanceB : advanceA;
    }

    /** What this side still owes on the pitch after its advance. */
    public long remainderFor(Side side) {
        return side == Side.B ? remainderB : remainderA;
    }

    /** What this side's card is charged in the app when it joins: fee + advance. */
    public long joinAmountFor(Side side) {
        return fee + advanceFor(side);
    }
}
