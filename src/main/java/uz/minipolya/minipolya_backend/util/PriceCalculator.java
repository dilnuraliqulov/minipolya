package uz.minipolya.minipolya_backend.util;

import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.PricingRule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Pure, stateless price calculation. A booking is one hour, priced by the active rule
 * covering it on its local weekday; when none does, by the stadium's default
 * {@code pricePerHour}. All money is tiyin (1 so'm = 100 tiyin).
 */
@Component
public class PriceCalculator {

    /**
     * @param activeRules         active pricing rules for the stadium (any day)
     * @param date                the hour's local date (its weekday selects the rules)
     * @param startTime           the hour's local start
     * @param defaultPricePerHour stadium fallback price when no rule covers the hour
     * @return the hour's price in tiyin
     */
    public long hourPrice(List<PricingRule> activeRules, LocalDate date, LocalTime startTime,
                          long defaultPricePerHour) {
        return priceForHour(activeRules, toDayOfWeek(date), startTime, defaultPricePerHour);
    }

    private long priceForHour(List<PricingRule> activeRules, int dayOfWeek, LocalTime slotStart, long defaultPrice) {
        if (activeRules != null) {
            for (PricingRule rule : activeRules) {
                if (rule.isActive()
                        && rule.getDayOfWeek() != null
                        && rule.getDayOfWeek() == dayOfWeek
                        && covers(rule, slotStart)) {
                    return rule.getPricePerHour();
                }
            }
        }
        return defaultPrice;
    }

    /** A rule covers an hour when its start lies within [rule.start, rule.end). */
    private boolean covers(PricingRule rule, LocalTime slotStart) {
        return !slotStart.isBefore(rule.getStartTime()) && slotStart.isBefore(rule.getEndTime());
    }

    // ---- Card fee ----

    /** Basis points in 100%. */
    public static final int BPS_SCALE = 10_000;

    /**
     * <b>The</b> conversion from an owner's price to the one price players see and pay — the
     * only place it is calculated ({@code CardPricing} applies it with the settings in force).
     * Grossed up by the card fee so that after ATMOS takes {@code cardFeeBps} the recipients
     * still get {@code net}, then rounded up to the next multiple of {@code roundingStep}.
     * Integer math only.
     *
     * <pre>D = playerAmount(net) = roundUp( ceil(net * 10000 / (10000 - bps)), step )</pre>
     *
     * For example 15 000 000 tiyin (150 000 so'm) at 150 bps, step 500 000 → 15 500 000
     * (155 000 so'm); with step 10 000 → 15 230 000 (152 300 so'm).
     *
     * @param net          tiyin, not negative
     * @param cardFeeBps   0..9999 (150 = 1.5%)
     * @param roundingStep tiyin, at least 1 (1 = no rounding)
     * @throws ArithmeticException rather than overflow silently (needs net above ~9×10^14 tiyin)
     */
    public static long playerAmount(long net, int cardFeeBps, long roundingStep) {
        if (roundingStep < 1) {
            throw new IllegalArgumentException("roundingStep must be at least 1");
        }
        return roundUp(grossUp(net, cardFeeBps), roundingStep);
    }

    /** {@code ceil(net * 10000 / (10000 - bps))}: the least amount that still leaves {@code net} after the fee. */
    public static long grossUp(long net, int cardFeeBps) {
        if (net < 0) {
            throw new IllegalArgumentException("net must not be negative");
        }
        if (cardFeeBps < 0 || cardFeeBps >= BPS_SCALE) {
            throw new IllegalArgumentException("cardFeeBps must be 0..9999");
        }
        return Math.ceilDiv(Math.multiplyExact(net, BPS_SCALE), BPS_SCALE - cardFeeBps);
    }

    /**
     * What a recipient keeps of a card split of {@code gross} after ATMOS takes
     * {@code cardFeeBps} of it: {@code floor(gross * (10000 - bps) / 10000)}. The inverse of
     * {@link #grossUp}: {@code netAfterFee(grossUp(n)) == n} for every n.
     */
    public static long netAfterFee(long gross, int cardFeeBps) {
        if (cardFeeBps < 0 || cardFeeBps >= BPS_SCALE) {
            throw new IllegalArgumentException("cardFeeBps must be 0..9999");
        }
        return Math.floorDiv(Math.multiplyExact(gross, BPS_SCALE - cardFeeBps), BPS_SCALE);
    }

    /** The smallest multiple of {@code step} that is at least {@code amount}. */
    public static long roundUp(long amount, long step) {
        return Math.multiplyExact(Math.ceilDiv(amount, step), step);
    }

    /** Converts a date's weekday to the app convention: 0 = Monday .. 6 = Sunday. */
    public int toDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }
}
