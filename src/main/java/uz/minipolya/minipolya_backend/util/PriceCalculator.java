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

    /** Converts a date's weekday to the app convention: 0 = Monday .. 6 = Sunday. */
    public int toDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }
}
