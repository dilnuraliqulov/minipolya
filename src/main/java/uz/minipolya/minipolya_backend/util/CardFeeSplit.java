package uz.minipolya.minipolya_backend.util;

/**
 * How one card payment divides between the stadium owner and the platform. Every figure
 * is tiyin.
 *
 * <ul>
 *   <li>An <b>ordinary booking</b> (and a friends game without a service fee): the platform
 *       takes nothing — the whole payment is the owner's split ({@link #toOwner}). ATMOS
 *       takes its fee from it; the rounding remainder is the owner's.</li>
 *   <li>A <b>team game</b> (and a friends game with a service fee): the platform's split is
 *       its fixed fee grossed up, {@code ceil(fee * 10000 / (10000 - bps))}, so after ATMOS
 *       the platform nets exactly that fee; everything else, rounding included, is the
 *       owner's ({@link #withPlatformFee}).</li>
 * </ul>
 *
 * {@code ownerAmount + platformAmount == playerAmount} always, and neither is negative.
 *
 * @param playerAmount   what the card is charged
 * @param ownerAmount    the owner's split
 * @param platformAmount the platform's split
 * @param atmosFee       what ATMOS is expected to take: its rate on each split
 */
public record CardFeeSplit(long playerAmount, long ownerAmount, long platformAmount, long atmosFee) {

    public CardFeeSplit {
        if (ownerAmount < 0 || platformAmount < 0) {
            throw new IllegalStateException("a split is negative: owner " + ownerAmount
                    + ", platform " + platformAmount);
        }
        if (ownerAmount + platformAmount != playerAmount) {
            throw new IllegalStateException("split does not add up: " + ownerAmount + " + " + platformAmount
                    + " != " + playerAmount);
        }
    }

    /** The whole payment to the owner: an ordinary booking, a remainder, a fee-free friends game. */
    public static CardFeeSplit toOwner(long playerAmount, int cardFeeBps) {
        return new CardFeeSplit(playerAmount, playerAmount, 0,
                playerAmount - PriceCalculator.netAfterFee(playerAmount, cardFeeBps));
    }

    /**
     * The platform's fixed fee grossed up to its split, the rest to the owner. A fee of 0
     * is {@link #toOwner}.
     */
    public static CardFeeSplit withPlatformFee(long playerAmount, long platformFee, int cardFeeBps) {
        if (platformFee <= 0) {
            return toOwner(playerAmount, cardFeeBps);
        }
        long platform = PriceCalculator.grossUp(platformFee, cardFeeBps);
        long owner = playerAmount - platform;
        long fee = playerAmount - PriceCalculator.netAfterFee(owner, cardFeeBps)
                - PriceCalculator.netAfterFee(platform, cardFeeBps);
        return new CardFeeSplit(playerAmount, owner, platform, fee);
    }

    /** What the owner keeps of this payment once ATMOS has taken its fee. */
    public long ownerReceives(int cardFeeBps) {
        return PriceCalculator.netAfterFee(ownerAmount, cardFeeBps);
    }

    /** What the platform keeps of this payment once ATMOS has taken its fee. */
    public long platformReceives(int cardFeeBps) {
        return PriceCalculator.netAfterFee(platformAmount, cardFeeBps);
    }
}
