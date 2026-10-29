package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RevenueTest {

    private static void assertAddsUp(Revenue r) {
        assertThat(r.ownerPayouts() + r.platformRevenue() + r.cardFees())
                .as("ownerPayouts + platformRevenue + cardFees = appRevenue")
                .isEqualTo(r.appRevenue());
    }

    @Test
    void theOwnerAndThePlatformEachBearTheFeeOnTheirOwnSplit() {
        // A 200 000 so'm booking by card: 203 100 so'm charged, owner split 203 046.70 so'm
        // (grossed up so the owner nets 200 000 so'm), ATMOS takes 1.5% of each split.
        Revenue line = Revenue.line(20_310_000, 20_304_569, 5_431, 304_650);

        assertThat(line.appRevenue()).isEqualTo(20_310_000);
        assertThat(line.cardFees()).isEqualTo(304_650);
        // 304 650 × 20 304 569 / 20 310 000 = 304 568 (truncated)
        assertThat(line.ownerPayouts()).isEqualTo(20_304_569 - 304_568);
        assertThat(line.ownerPayouts()).isEqualTo(20_000_001);
        assertThat(line.platformRevenue()).isEqualTo(5_431 - 82);
        assertAddsUp(line);
    }

    @Test
    void aSampleOfPaymentsAndRefundsAddsUp() {
        List<Revenue> lines = List.of(
                Revenue.line(20_310_000, 20_304_569, 5_431, 304_650),     // booking in full
                Revenue.line(10_160_000, 10_152_285, 7_715, 152_400),     // deposit
                Revenue.line(7_620_000, 5_076_143, 2_543_857, 114_300),   // team game: advance + fee
                Revenue.line(3_050_000, 3_045_686, 4_314, 45_750),        // remainder in the app
                Revenue.line(-10_160_000, -10_152_285, -7_715, -152_400), // the deposit reversed
                Revenue.line(-3_050_000, -3_045_686, -4_314, 0),          // refunded by hand
                Revenue.line(1_000_001, 333_333, 666_668, 15_001));       // odd amounts

        Revenue total = Revenue.sum(lines);

        lines.forEach(RevenueTest::assertAddsUp);
        assertAddsUp(total);
        assertThat(total.appRevenue()).isEqualTo(20_310_000 + 7_620_000 + 1_000_001);
    }

    @Test
    void aPaymentReversedInFullNetsToZeroOnEveryFigure() {
        Revenue paid = Revenue.line(20_310_000, 20_304_569, 5_431, 304_650);
        Revenue reversed = Revenue.line(-20_310_000, -20_304_569, -5_431, -304_650);

        assertThat(paid.plus(reversed)).isEqualTo(Revenue.ZERO);
    }

    @Test
    void sumsBeyondTheIntRangeAreKept() {
        Revenue big = Revenue.sum(List.of(
                Revenue.line(2_000_000_000L, 1_900_000_000L, 100_000_000L, 0),
                Revenue.line(2_000_000_000L, 1_900_000_000L, 100_000_000L, 0)));

        assertThat(big.appRevenue()).isEqualTo(4_000_000_000L);
    }
}
