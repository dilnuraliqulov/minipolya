package uz.minipolya.minipolya_backend.util;

/**
 * How one card payment divides between the stadium owner and the platform once the
 * card fee is included. Every figure is tiyin.
 *
 * <ul>
 *   <li>{@code ownerGross = ceil(ownerNet * 10000 / (10000 - bps))} — after the provider
 *       takes its fee from the owner's split, the owner gets exactly {@code ownerNet};</li>
 *   <li>{@code platformGross = playerAmount - ownerGross} — the platform's own net share
 *       grossed up, plus whatever the rounding step added.</li>
 * </ul>
 *
 * {@code playerAmount == ownerGross + platformGross} always holds, and {@code cardFee}
 * ({@code playerAmount - net}) is what the card fee and the rounding cost the player.
 *
 * @param net           what the payment is worth to its recipients: ownerNet + platformNet
 * @param ownerNet      what the owner must end up with
 * @param platformNet   what the platform must end up with (commission, team fee, service fee)
 * @param playerAmount  what the card is charged
 * @param ownerGross    the owner's split of the charge
 * @param platformGross the platform's split of the charge
 * @param cardFee       playerAmount − net: the fee plus the rounding remainder
 */
public record CardFeeSplit(long net,
                           long ownerNet,
                           long platformNet,
                           long playerAmount,
                           long ownerGross,
                           long platformGross,
                           long cardFee) {

    public CardFeeSplit {
        if (ownerGross + platformGross != playerAmount) {
            throw new IllegalStateException("split does not add up: " + ownerGross + " + " + platformGross
                    + " != " + playerAmount);
        }
        if (platformGross < 0) {
            throw new IllegalStateException("the owner's grossed-up share exceeds what the player pays");
        }
    }

    /**
     * The split of an already-known player amount — for an ordinary booking paid in full,
     * that amount is the sum of each slot's {@code playerAmount}, not one rounding of the total.
     */
    public static CardFeeSplit of(long playerAmount, long ownerNet, long platformNet, int cardFeeBps) {
        long ownerGross = PriceCalculator.grossUp(ownerNet, cardFeeBps);
        long net = ownerNet + platformNet;
        return new CardFeeSplit(net, ownerNet, platformNet, playerAmount, ownerGross,
                playerAmount - ownerGross, playerAmount - net);
    }

    /** The split of a single net amount, charged as {@code playerAmount(ownerNet + platformNet)}. */
    public static CardFeeSplit forNet(long ownerNet, long platformNet, int cardFeeBps, long roundingStep) {
        long playerAmount = PriceCalculator.playerAmount(ownerNet + platformNet, cardFeeBps, roundingStep);
        return of(playerAmount, ownerNet, platformNet, cardFeeBps);
    }
}
