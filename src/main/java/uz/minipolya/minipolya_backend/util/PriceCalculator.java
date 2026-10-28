package uz.minipolya.minipolya_backend.util;

import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.PricingRule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Pure, stateless price calculation. Prices are computed hour by hour, so a
 * booking that spans two different price slots sums each hour's rate. When no
 * active rule matches a given hour, that hour falls back to the stadium's
 * default {@code pricePerHour}. All money is tiyin (1 so'm = 100 tiyin).
 *
 * <p>Each hour is priced against its own local weekday, so a span that crosses
 * midnight correctly switches to the next day's rules.
 */
@Component
public class PriceCalculator {

    /**
     * @param activeRules        active pricing rules for the stadium (any day)
     * @param date               the booking date (its weekday selects the rules)
     * @param startTime          booking start time
     * @param durationHours      whole hours, at least 1
     * @param defaultPricePerHour stadium fallback price for unmatched hours
     * @return total price in tiyin
     */
    public long calculateTotalPrice(List<PricingRule> activeRules,
                                    LocalDate date,
                                    LocalTime startTime,
                                    int durationHours,
                                    long defaultPricePerHour) {
        if (durationHours < 1) {
            throw new IllegalArgumentException("durationHours must be at least 1");
        }
        LocalDateTime cursor = LocalDateTime.of(date, startTime);

        long total = 0;
        for (int hour = 0; hour < durationHours; hour++) {
            LocalDateTime slot = cursor.plusHours(hour);
            int dayOfWeek = toDayOfWeek(slot.toLocalDate());
            total += priceForHour(activeRules, dayOfWeek, slot.toLocalTime(), defaultPricePerHour);
        }
        return total;
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
     * What a player pays by card for a net amount: grossed up by the card fee so that
     * after the provider takes {@code cardFeeBps} the recipients still get {@code net},
     * then rounded up to the next multiple of {@code roundingStep}. Integer math only.
     *
     * <pre>playerAmount(net) = roundUp( ceil(net * 10000 / (10000 - bps)), step )</pre>
     *
     * For example 20 000 000 tiyin (200 000 so'm) at 150 bps, step 10 000 →
     * ceil(20 304 568.5) = 20 304 569 → 20 310 000 (203 100 so'm).
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

    /** The smallest multiple of {@code step} that is at least {@code amount}. */
    public static long roundUp(long amount, long step) {
        return Math.multiplyExact(Math.ceilDiv(amount, step), step);
    }

    /** Converts a date's weekday to the app convention: 0 = Monday .. 6 = Sunday. */
    public int toDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }
}
