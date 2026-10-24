package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundCalculatorTest {

    private final RefundCalculator calc = new RefundCalculator();

    @Test
    void fullRefundAtOrBeyondFullWindow() {
        assertThat(calc.refundPercent(24, 24, 12, 50)).isEqualTo(100);
        assertThat(calc.refundPercent(100, 24, 12, 50)).isEqualTo(100);
    }

    @Test
    void partialRefundInsidePartialWindow() {
        assertThat(calc.refundPercent(23, 24, 12, 50)).isEqualTo(50);
        assertThat(calc.refundPercent(12, 24, 12, 50)).isEqualTo(50);
    }

    @Test
    void noRefundBelowPartialWindow() {
        assertThat(calc.refundPercent(11, 24, 12, 50)).isZero();
        assertThat(calc.refundPercent(0, 24, 12, 50)).isZero();
        assertThat(calc.refundPercent(-5, 24, 12, 50)).isZero();
    }

    @Test
    void refundAmountUsesIntegerSomRoundingDown() {
        assertThat(calc.refundAmount(100_000, 100)).isEqualTo(100_000);
        assertThat(calc.refundAmount(100_000, 50)).isEqualTo(50_000);
        assertThat(calc.refundAmount(100_000, 0)).isZero();
        assertThat(calc.refundAmount(99_999, 50)).isEqualTo(49_999); // 49999.5 floored
    }

    @Test
    void refundAmountAboveIntRangeIsExact() {
        assertThat(calc.refundAmount(3_000_000_000L, 100)).isEqualTo(3_000_000_000L);
    }
}
