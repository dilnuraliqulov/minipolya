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
 * Single source of truth for what a booking costs. A booking is one hour, priced by
 * the pricing rule matching its local weekday/time (falling back to
 * {@code stadium.pricePerHour}) with the best active promotion for that hour applied.
 *
 * <p>This mirrors exactly what {@link AvailabilityService} shows for the slot, so the
 * price charged for a booking always equals what the availability endpoint displays.
 */
@Component
@RequiredArgsConstructor
public class BookingPriceCalculator {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private final PriceCalculator priceCalculator;
    private final PricingRuleRepository pricingRuleRepository;
    private final PromotionService promotionService;

    /**
     * The owner's price of the one-hour booking starting at {@code startsAt}, in tiyin. Its
     * player price is {@code playerAmount} of it, the same figure the slot shows.
     */
    public long hourPrice(Stadium stadium, OffsetDateTime startsAt) {
        List<PricingRule> rules = pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadium.getId());
        ZonedDateTime slot = startsAt.atZoneSameInstant(TASHKENT);
        return finalHourPrice(stadium, rules, slot.toLocalDate(), slot.toLocalTime(), slot.toOffsetDateTime());
    }

    /** Final price for one hour, in tiyin: base rule/fallback price minus the best promotion covering it. */
    public long finalHourPrice(Stadium stadium, List<PricingRule> rules,
                               LocalDate date, LocalTime slotStart, OffsetDateTime slotStartUtc) {
        long base = priceCalculator.hourPrice(rules, date, slotStart, stadium.getPricePerHour());
        return promotionService.getBestPromotion(stadium.getId(), slotStartUtc)
                .map(promo -> applyDiscount(base, promo.getDiscountPercent()))
                .orElse(base);
    }

    /** Tiyin discount: percent off the base price, rounded down. */
    public static long applyDiscount(long base, int discountPercent) {
        return base - (base * discountPercent / 100);
    }
}
