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

    /** Total price for a booking, in tiyin: the sum of each hour's promotion-adjusted price. */
    public long totalPrice(Stadium stadium, OffsetDateTime startsAt, int durationHours) {
        return slotPrices(stadium, startsAt, durationHours).stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Each hour's net (owner) price, in order. A booking's card price is the sum of each
     * of these grossed up on its own — the same figures the availability endpoint shows —
     * so the slot prices a player sees add up to exactly what they are charged.
     */
    public List<Long> slotPrices(Stadium stadium, OffsetDateTime startsAt, int durationHours) {
        List<PricingRule> rules = pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadium.getId());
        ZonedDateTime start = startsAt.atZoneSameInstant(TASHKENT);
        List<Long> prices = new java.util.ArrayList<>(durationHours);
        for (int hour = 0; hour < durationHours; hour++) {
            ZonedDateTime slot = start.plusHours(hour);
            prices.add(finalHourPrice(stadium, rules, slot.toLocalDate(), slot.toLocalTime(), slot.toOffsetDateTime()));
        }
        return prices;
    }

    /** Final price for one hour, in tiyin: base rule/fallback price minus the best promotion covering it. */
    public long finalHourPrice(Stadium stadium, List<PricingRule> rules,
                               LocalDate date, LocalTime slotStart, OffsetDateTime slotStartUtc) {
        long base = priceCalculator.calculateTotalPrice(rules, date, slotStart, 1, stadium.getPricePerHour());
        return promotionService.getBestPromotion(stadium.getId(), slotStartUtc)
                .map(promo -> applyDiscount(base, promo.getDiscountPercent()))
                .orElse(base);
    }

    /** Tiyin discount: percent off the base price, rounded down. */
    public static long applyDiscount(long base, int discountPercent) {
        return base - (base * discountPercent / 100);
    }
}
