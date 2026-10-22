package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.PricingRule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceCalculatorTest {

    // 2026-10-12 is a Monday -> dayOfWeek 0 in the app convention.
    private static final LocalDate DATE = LocalDate.of(2026, 10, 12);
    private static final int DEFAULT_PRICE = 80_000;

    private PriceCalculator calculator;
    private int dayOfWeek;

    @BeforeEach
    void setUp() {
        calculator = new PriceCalculator();
        dayOfWeek = calculator.toDayOfWeek(DATE);
    }

    @Test
    void dateMapsMondayToZero() {
        assertThat(calculator.toDayOfWeek(DATE)).isEqualTo(0);
        assertThat(calculator.toDayOfWeek(DATE.plusDays(6))).isEqualTo(6); // Sunday
    }

    @Test
    void fallsBackToDefaultWhenNoRuleMatches() {
        int total = calculator.calculateTotalPrice(List.of(), DATE, LocalTime.of(10, 0), 2, DEFAULT_PRICE);
        assertThat(total).isEqualTo(160_000);
    }

    @Test
    void usesMatchingRulePriceForOneHour() {
        PricingRule rule = rule(dayOfWeek, LocalTime.of(18, 0), LocalTime.of(22, 0), 120_000, true);

        int total = calculator.calculateTotalPrice(List.of(rule), DATE, LocalTime.of(18, 0), 1, DEFAULT_PRICE);

        assertThat(total).isEqualTo(120_000);
    }

    @Test
    void twoHourBookingSumsAcrossTwoPriceSlots() {
        PricingRule evening = rule(dayOfWeek, LocalTime.of(18, 0), LocalTime.of(22, 0), 100_000, true);
        PricingRule lateNight = rule(dayOfWeek, LocalTime.of(22, 0), LocalTime.of(23, 59), 150_000, true);

        // 21:00-23:00 -> hour 21:00 (evening) + hour 22:00 (lateNight)
        int total = calculator.calculateTotalPrice(List.of(evening, lateNight), DATE, LocalTime.of(21, 0), 2, DEFAULT_PRICE);

        assertThat(total).isEqualTo(250_000);
    }

    @Test
    void twoHourBookingMixesRuleAndDefault() {
        PricingRule evening = rule(dayOfWeek, LocalTime.of(18, 0), LocalTime.of(22, 0), 100_000, true);

        // 21:00-23:00 -> hour 21:00 (rule=100k) + hour 22:00 (no rule -> default 80k)
        int total = calculator.calculateTotalPrice(List.of(evening), DATE, LocalTime.of(21, 0), 2, DEFAULT_PRICE);

        assertThat(total).isEqualTo(180_000);
    }

    @Test
    void crossingMidnightPricesEachHourAgainstItsOwnWeekday() {
        LocalDate sunday = DATE.minusDays(1); // 2026-10-11 is a Sunday -> dayOfWeek 6
        int sundayDow = calculator.toDayOfWeek(sunday);
        int mondayDow = calculator.toDayOfWeek(DATE);
        PricingRule sundayLate = rule(sundayDow, LocalTime.of(23, 0), LocalTime.of(23, 59), 200_000, true);
        PricingRule mondayEarly = rule(mondayDow, LocalTime.of(0, 0), LocalTime.of(6, 0), 90_000, true);

        // 23:00 Sunday for 2h -> hour 23:00 (Sunday rule) + hour 00:00 (Monday rule)
        int total = calculator.calculateTotalPrice(
                List.of(sundayLate, mondayEarly), sunday, LocalTime.of(23, 0), 2, DEFAULT_PRICE);

        assertThat(total).isEqualTo(290_000);
    }

    @Test
    void ignoresRulesForOtherDays() {
        PricingRule otherDay = rule((dayOfWeek + 1) % 7, LocalTime.of(18, 0), LocalTime.of(22, 0), 500_000, true);

        int total = calculator.calculateTotalPrice(List.of(otherDay), DATE, LocalTime.of(18, 0), 1, DEFAULT_PRICE);

        assertThat(total).isEqualTo(DEFAULT_PRICE);
    }

    @Test
    void ignoresInactiveRules() {
        PricingRule inactive = rule(dayOfWeek, LocalTime.of(18, 0), LocalTime.of(22, 0), 500_000, false);

        int total = calculator.calculateTotalPrice(List.of(inactive), DATE, LocalTime.of(18, 0), 1, DEFAULT_PRICE);

        assertThat(total).isEqualTo(DEFAULT_PRICE);
    }

    private PricingRule rule(int dow, LocalTime start, LocalTime end, int price, boolean active) {
        return PricingRule.builder()
                .dayOfWeek(dow)
                .startTime(start)
                .endTime(end)
                .pricePerHour(price)
                .isActive(active)
                .build();
    }
}
