package uz.minipolya.minipolya_backend.util;

import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;

/**
 * What one side of a team-vs-team game may pay (T4). Every figure is tiyin.
 *
 * <ul>
 *   <li><b>minimum</b>: net {@code M} (the stadium's effective minimum) — the platform fee
 *       {@code F} plus {@code M − F} for the owner;</li>
 *   <li><b>full</b>: net {@code P/2 + F} — the side's half of the pitch price {@code P}
 *       (the odd tiyin of an odd P goes to side B) plus the fee.</li>
 * </ul>
 *
 * The player pays {@code playerAmount(net)}. After a minimum payment the remainder is
 * {@code playerAmount(fullNet) − paid} — never rounded on its own, all the owner's. The
 * minimum is offered only when {@code F < M < fullNet} and its player amount is below the
 * full one; otherwise only full is.
 *
 * <p>Pure: the one place this arithmetic is expressed, used by the hold, the charge and the
 * quotes alike.
 */
public record TeamPaymentQuote(Side side,
                               long fee,
                               long minimumNet,
                               long fullNet,
                               long minimumPrice,
                               long fullPrice,
                               boolean minimumOffered) {

    /**
     * @param price            the pitch price for the whole game, owner's terms
     * @param effectiveMinimum the stadium's effective minimum booking amount (its own or the default)
     * @param fee              the per-team platform fee
     */
    public static TeamPaymentQuote of(long price, long effectiveMinimum, long fee, Side side,
                                      int cardFeeBps, long roundingStep) {
        long half = side == Side.B ? price - price / 2 : price / 2;
        long fullNet = half + fee;
        long fullPrice = PriceCalculator.playerAmount(fullNet, cardFeeBps, roundingStep);
        boolean offered = effectiveMinimum > fee && effectiveMinimum < fullNet;
        long minimumPrice = offered ? PriceCalculator.playerAmount(effectiveMinimum, cardFeeBps, roundingStep) : fullPrice;
        if (minimumPrice >= fullPrice) {
            offered = false;
            minimumPrice = fullPrice;
        }
        return new TeamPaymentQuote(side, fee, offered ? effectiveMinimum : fullNet, fullNet,
                minimumPrice, fullPrice, offered);
    }

    /** What is left to pay after the minimum: the full player amount less the minimum one; 0 when not offered. */
    public long minimumRemainder() {
        return fullPrice - minimumPrice;
    }

    /**
     * The requested option, or {@code FULL} when the minimum is not offered (also for a request
     * without one, which means minimum).
     */
    public Choice choose(GameTeam.PaymentOption requested) {
        GameTeam.PaymentOption option = requested != GameTeam.PaymentOption.FULL && minimumOffered
                ? GameTeam.PaymentOption.MINIMUM
                : GameTeam.PaymentOption.FULL;
        long net = option == GameTeam.PaymentOption.MINIMUM ? minimumNet : fullNet;
        long joinPrice = option == GameTeam.PaymentOption.MINIMUM ? minimumPrice : fullPrice;
        return new Choice(option, fee, net - fee, fullNet - net, joinPrice, fullPrice - joinPrice);
    }

    /**
     * One team's payment, decided.
     *
     * @param ownerAdvance   the owner's part of the joining payment, owner's terms ({@code net − F})
     * @param ownerRemainder the owner's part still owed, owner's terms ({@code fullNet − net})
     * @param joinPrice      what the card is held or charged on joining
     * @param remainderPrice what is still owed, as the player pays it (app or cash)
     */
    public record Choice(GameTeam.PaymentOption option, long fee, long ownerAdvance, long ownerRemainder,
                         long joinPrice, long remainderPrice) {

        /** The joining payment's split: the platform's nets exactly the fee, the rest is the owner's. */
        public CardFeeSplit joinSplit(int cardFeeBps) {
            return CardFeeSplit.withPlatformFee(joinPrice, fee, cardFeeBps);
        }
    }
}
