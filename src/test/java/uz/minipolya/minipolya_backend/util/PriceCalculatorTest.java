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
        assertThat(calculator.hourPrice(List.of(), DATE, LocalTime.of(10, 0), DEFAULT_PRICE)).isEqualTo(DEFAULT_PRICE);
    }

    @Test
    void usesTheMatchingRulesPrice() {
        PricingRule rule = rule(dayOfWeek, LocalTime.of(18, 0), LocalTime.of(22, 0), 120_000, true);

        assertThat(calculator.hourPrice(List.of(rule), DATE, LocalTime.of(18, 0), DEFAULT_PRICE)).isEqualTo(120_000);
        assertThat(calculator.hourPrice(List.of(rule), DATE, LocalTime.of(21, 0), DEFAULT_PRICE)).isEqualTo(120_000);
        // The rule's end is exclusive.
        assertThat(calculator.hourPrice(List.of(rule), DATE, LocalTime.of(22, 0), DEFAULT_PRICE)).isEqualTo(DEFAULT_PRICE);
    }

    @Test
    void ignoresRulesForOtherDays() {
        PricingRule otherDay = rule((dayOfWeek + 1) % 7, LocalTime.of(18, 0), LocalTime.of(22, 0), 500_000, true);

        assertThat(calculator.hourPrice(List.of(otherDay), DATE, LocalTime.of(18, 0), DEFAULT_PRICE)).isEqualTo(DEFAULT_PRICE);
    }

    @Test
    void ignoresInactiveRules() {
        PricingRule inactive = rule(dayOfWeek, LocalTime.of(18, 0), LocalTime.of(22, 0), 500_000, false);

        assertThat(calculator.hourPrice(List.of(inactive), DATE, LocalTime.of(18, 0), DEFAULT_PRICE)).isEqualTo(DEFAULT_PRICE);
    }

    @Test
    void aPriceAboveIntRangeIsExact() {
        assertThat(calculator.hourPrice(List.of(), DATE, LocalTime.of(0, 0), 3_000_000_000L)).isEqualTo(3_000_000_000L);
    }

    private PricingRule rule(int dow, LocalTime start, LocalTime end, long price, boolean active) {
        return PricingRule.builder()
                .dayOfWeek(dow)
                .startTime(start)
                .endTime(end)
                .pricePerHour(price)
                .isActive(active)
                .build();
    }
}
