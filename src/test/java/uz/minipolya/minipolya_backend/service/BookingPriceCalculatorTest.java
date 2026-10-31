package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Promotion;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingPriceCalculatorTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final UUID STADIUM_ID = UUID.randomUUID();
    // 2026-10-12 is a Monday (dayOfWeek 0 in the app convention).
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate SUNDAY = MONDAY.minusDays(1);

    private PricingRuleRepository pricingRuleRepository;
    private PromotionService promotionService;
    private BookingPriceCalculator calculator;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        pricingRuleRepository = mock(PricingRuleRepository.class);
        promotionService = mock(PromotionService.class);
        calculator = new BookingPriceCalculator(new PriceCalculator(), pricingRuleRepository, promotionService);
        stadium = Stadium.builder().id(STADIUM_ID).pricePerHour(50_000L).build();
        // Default: no promotion for any hour.
        when(promotionService.getBestPromotion(eq(STADIUM_ID), any())).thenReturn(Optional.empty());
    }

    private int dow(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }

    private OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    @Test
    void anHourCoveredByARuleTakesTheRulesPrice() {
        PricingRule evening = PricingRule.builder().dayOfWeek(dow(MONDAY))
                .startTime(LocalTime.of(19, 0)).endTime(LocalTime.of(23, 0))
                .pricePerHour(80_000L).isActive(true).build();
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(evening));

        assertThat(calculator.hourPrice(stadium, at(MONDAY, 19))).isEqualTo(80_000);
        // 18:00 is outside the rule: the stadium's default.
        assertThat(calculator.hourPrice(stadium, at(MONDAY, 18))).isEqualTo(50_000);
    }

    @Test
    void aPromotionOnTheHourIsApplied() {
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of());
        when(promotionService.getBestPromotion(eq(STADIUM_ID), eq(at(MONDAY, 19))))
                .thenReturn(Optional.of(Promotion.builder().discountPercent(20).name("Happy Hour").build()));

        assertThat(calculator.hourPrice(stadium, at(MONDAY, 19))).isEqualTo(40_000); // 50k - 20%
        assertThat(calculator.hourPrice(stadium, at(MONDAY, 18))).isEqualTo(50_000);
    }

    @Test
    void anHourIsPricedOnItsOwnLocalWeekday() {
        PricingRule sundayLate = PricingRule.builder().dayOfWeek(dow(SUNDAY))
                .startTime(LocalTime.of(23, 0)).endTime(LocalTime.of(23, 59))
                .pricePerHour(200_000L).isActive(true).build();
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(sundayLate));

        assertThat(calculator.hourPrice(stadium, at(SUNDAY, 23))).isEqualTo(200_000);
        assertThat(calculator.hourPrice(stadium, at(MONDAY, 23))).isEqualTo(50_000);
    }

    @Test
    void aPriceAboveIntRangeIsExact() {
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of());
        Stadium expensive = Stadium.builder().id(STADIUM_ID).pricePerHour(3_000_000_000L).build();

        assertThat(calculator.hourPrice(expensive, at(MONDAY, 10))).isEqualTo(3_000_000_000L);
    }
}
