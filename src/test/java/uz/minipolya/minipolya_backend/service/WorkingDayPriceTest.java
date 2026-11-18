package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Promotion;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;
import uz.minipolya.minipolya_backend.util.WeeklyHours;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * An hour after midnight is priced and discounted by its <b>working</b> day, not by the
 * calendar day it falls on. 2026-11-21 is a Saturday (dayOfWeek 5), 2026-11-22 a Sunday (6).
 */
@ExtendWith(MockitoExtension.class)
class WorkingDayPriceTest {

    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 21);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 22);
    private static final int SATURDAY_DOW = 5;
    private static final int SUNDAY_DOW = 6;

    @Mock
    private PricingRuleRepository pricingRuleRepository;
    @Mock
    private PromotionService promotionService;

    private BookingPriceCalculator calculator;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        calculator = new BookingPriceCalculator(new PriceCalculator(), pricingRuleRepository, promotionService);
        stadium = Stadium.builder().id(STADIUM_ID).pricePerHour(50_000L).build();
        // Open 08:00-02:00 every day, so 00:00 and 01:00 belong to the day before.
        stadium.setWeeklyHours(WeeklyHours.sameEveryDay(LocalTime.of(8, 0), LocalTime.of(2, 0)));
        lenient().when(promotionService.getBestPromotion(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
    }

    private static PricingRule rule(int dayOfWeek, int fromHour, int toHour, long price) {
        return PricingRule.builder()
                .dayOfWeek(dayOfWeek)
                .startTime(LocalTime.of(fromHour, 0))
                .endTime(LocalTime.of(toHour, 0))
                .pricePerHour(price)
                .isActive(true)
                .build();
    }

    private static OffsetDateTime tashkent(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(WeeklyHours.TASHKENT).toOffsetDateTime();
    }

    @Test
    void theMidnightSlotUsesTheWorkingDaysPriceRules() {
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(
                rule(SATURDAY_DOW, 0, 2, 200_000L),
                rule(SUNDAY_DOW, 0, 2, 90_000L)));

        // 00:00 on Sunday is Saturday's 00:00 slot.
        assertThat(calculator.hourPrice(stadium, tashkent(SUNDAY, 0))).isEqualTo(200_000L);
    }

    @Test
    void aSameDaySlotIsUnchanged() {
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(
                rule(SATURDAY_DOW, 20, 23, 300_000L)));

        assertThat(calculator.hourPrice(stadium, tashkent(SATURDAY, 20))).isEqualTo(300_000L);
    }

    @Test
    void anHourWithNoRuleFallsBackToTheStadiumPrice() {
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(
                rule(SUNDAY_DOW, 0, 2, 90_000L)));

        // Saturday has no rule for 01:00, so the stadium's own price stands - never Sunday's.
        assertThat(calculator.hourPrice(stadium, tashkent(SUNDAY, 1))).isEqualTo(50_000L);
    }

    @Test
    void theMidnightSlotAsksForTheWorkingDaysPromotion() {
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of());
        when(promotionService.getBestPromotion(eq(STADIUM_ID), any(), eq(SATURDAY_DOW)))
                .thenReturn(Optional.of(Promotion.builder().discountPercent(50).build()));

        assertThat(calculator.hourPrice(stadium, tashkent(SUNDAY, 0))).isEqualTo(25_000L);
    }

    @Test
    void anHourOutsideEveryWindowStillPricesByItsOwnDate() {
        // A booking stored before the stadium's hours changed: 05:00 is open on no day.
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(
                rule(SUNDAY_DOW, 5, 6, 70_000L)));

        assertThat(calculator.hourPrice(stadium, tashkent(SUNDAY, 5))).isEqualTo(70_000L);
    }
}
