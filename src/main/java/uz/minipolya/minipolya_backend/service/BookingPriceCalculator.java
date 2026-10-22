package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Single source of truth for what a booking costs. The total is the sum of each
 * 1-hour slot's price, where every hour uses the pricing rule matching its own
 * local weekday/time (falling back to {@code stadium.pricePerHour}) and then has
 * the best active promotion for that hour applied.
 *
 * <p>This mirrors exactly what {@link AvailabilityService} shows per slot, so the
 * price charged for a booking always equals the sum the availability endpoint
 * displays for the same hours.
 */
@Component
@RequiredArgsConstructor
public class BookingPriceCalculator {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private final PriceCalculator priceCalculator;
    private final PricingRuleRepository pricingRuleRepository;
    private final PromotionService promotionService;

    /** Total price for a booking: the sum of each hour's promotion-adjusted price. */
    public int totalPrice(Stadium stadium, OffsetDateTime startsAt, int durationHours) {
        List<PricingRule> rules = pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadium.getId());
        ZonedDateTime start = startsAt.atZoneSameInstant(TASHKENT);
        int total = 0;
        for (int hour = 0; hour < durationHours; hour++) {
            ZonedDateTime slot = start.plusHours(hour);
            total += finalHourPrice(stadium, rules, slot.toLocalDate(), slot.toLocalTime(), slot.toOffsetDateTime());
        }
        return total;
    }

    /** Final price for one hour: base rule/fallback price minus the best promotion covering it. */
    public int finalHourPrice(Stadium stadium, List<PricingRule> rules,
                              LocalDate date, LocalTime slotStart, OffsetDateTime slotStartUtc) {
        int base = priceCalculator.calculateTotalPrice(rules, date, slotStart, 1, stadium.getPricePerHour());
        return promotionService.getBestPromotion(stadium.getId(), slotStartUtc)
                .map(promo -> applyDiscount(base, promo.getDiscountPercent()))
                .orElse(base);
    }

    /** Integer-so'm discount: percent off the base price, rounded down. */
    public static int applyDiscount(int base, int discountPercent) {
        return base - (int) ((long) base * discountPercent / 100);
    }
}
