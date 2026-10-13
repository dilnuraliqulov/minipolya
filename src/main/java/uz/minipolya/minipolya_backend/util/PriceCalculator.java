package uz.minipolya.minipolya_backend.util;

import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.PricingRule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Pure, stateless price calculation. Prices are computed hour by hour, so a
 * booking that spans two different price slots sums each hour's rate. When no
 * active rule matches a given hour, that hour falls back to the stadium's
 * default {@code pricePerHour}. All money is integer so'm.
 */
@Component
public class PriceCalculator {

    /**
     * @param activeRules        active pricing rules for the stadium (any day)
     * @param date               the booking date (its weekday selects the rules)
     * @param startTime          booking start time
     * @param durationHours      1 or 2
     * @param defaultPricePerHour stadium fallback price for unmatched hours
     * @return total price in so'm
     */
    public int calculateTotalPrice(List<PricingRule> activeRules,
                                   LocalDate date,
                                   LocalTime startTime,
                                   int durationHours,
                                   int defaultPricePerHour) {
        if (durationHours < 1) {
            throw new IllegalArgumentException("durationHours must be at least 1");
        }
        int dayOfWeek = toDayOfWeek(date);

        int total = 0;
        for (int hour = 0; hour < durationHours; hour++) {
            LocalTime slotStart = startTime.plusHours(hour);
            total += priceForHour(activeRules, dayOfWeek, slotStart, defaultPricePerHour);
        }
        return total;
    }

    private int priceForHour(List<PricingRule> activeRules, int dayOfWeek, LocalTime slotStart, int defaultPrice) {
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

    /** Converts a date's weekday to the app convention: 0 = Monday .. 6 = Sunday. */
    public int toDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }
}
