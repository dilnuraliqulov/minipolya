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
        stadium = Stadium.builder().id(STADIUM_ID).pricePerHour(50_000).build();
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
    void threeHourBookingCrossingPricingBoundarySumsPerHour() {
        PricingRule evening = PricingRule.builder().dayOfWeek(dow(MONDAY))
                .startTime(LocalTime.of(19, 0)).endTime(LocalTime.of(23, 0))
                .pricePerHour(80_000).isActive(true).build();
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(evening));

        // 18:00-21:00 -> 18:00 default 50k + 19:00 rule 80k + 20:00 rule 80k
        int total = calculator.totalPrice(stadium, at(MONDAY, 18), 3);

        assertThat(total).isEqualTo(210_000);
    }

    @Test
    void promotionIsAppliedPerHourNotToTheWholeBooking() {
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of());
        // 20% off only on the 19:00 hour.
        when(promotionService.getBestPromotion(eq(STADIUM_ID), eq(at(MONDAY, 19))))
                .thenReturn(Optional.of(Promotion.builder().discountPercent(20).name("Happy Hour").build()));

        // 18:00 (50k) + 19:00 (50k - 20% = 40k) + 20:00 (50k) = 140k
        int total = calculator.totalPrice(stadium, at(MONDAY, 18), 3);

        assertThat(total).isEqualTo(140_000);
    }

    @Test
    void pricingAcrossMidnightUsesEachHoursWeekday() {
        PricingRule sundayLate = PricingRule.builder().dayOfWeek(dow(SUNDAY))
                .startTime(LocalTime.of(23, 0)).endTime(LocalTime.of(23, 59))
                .pricePerHour(200_000).isActive(true).build();
        PricingRule mondayEarly = PricingRule.builder().dayOfWeek(dow(MONDAY))
                .startTime(LocalTime.of(0, 0)).endTime(LocalTime.of(6, 0))
                .pricePerHour(90_000).isActive(true).build();
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID))
                .thenReturn(List.of(sundayLate, mondayEarly));

        // 23:00 Sunday for 2h -> 23:00 Sunday (200k) + 00:00 Monday (90k)
        int total = calculator.totalPrice(stadium, at(SUNDAY, 23), 2);

        assertThat(total).isEqualTo(290_000);
    }

    @Test
    void fallsBackToStadiumDefaultWhenNoRules() {
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of());

        int total = calculator.totalPrice(stadium, at(MONDAY, 10), 4);

        assertThat(total).isEqualTo(200_000); // 4 x 50k
    }
}
