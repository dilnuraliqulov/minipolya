package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam.PaymentOption;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4: a team's minimum / full payment. Every figure is tiyin. The spec's example (fee 25 000,
 * card fee 1.5 %, step 5 000 so'm, P = 200 000, M = 50 000) must hold exactly, and the money
 * rules must hold for many prices and minimums, odd and even.
 */
class TeamPaymentQuoteTest {

    private static final int BPS = 150;
    private static final long STEP = 500_000L;
    private static final long FEE = 2_500_000L;

    private static TeamPaymentQuote quote(long price, long minimum, Side side) {
        return TeamPaymentQuote.of(price, minimum, FEE, side, BPS, STEP);
    }

    @Test
    void theSpecExample() {
        for (Side side : Side.values()) {
            TeamPaymentQuote q = quote(20_000_000L, 5_000_000L, side);
            assertThat(q.minimumOffered()).isTrue();

            TeamPaymentQuote.Choice minimum = q.choose(PaymentOption.MINIMUM);
            assertThat(minimum.option()).isEqualTo(PaymentOption.MINIMUM);
            assertThat(minimum.joinPrice()).isEqualTo(5_500_000L);        // 55 000 so'm
            assertThat(minimum.remainderPrice()).isEqualTo(7_500_000L);   // 130 000 - 55 000 = 75 000
            assertThat(minimum.fee()).isEqualTo(2_500_000L);
            assertThat(minimum.ownerAdvance()).isEqualTo(2_500_000L);     // owner part 25 000
            assertThat(minimum.joinSplit(BPS).platformReceives(BPS)).isEqualTo(2_500_000L);

            TeamPaymentQuote.Choice full = q.choose(PaymentOption.FULL);
            assertThat(full.option()).isEqualTo(PaymentOption.FULL);
            assertThat(full.joinPrice()).isEqualTo(13_000_000L);          // 130 000 so'm
            assertThat(full.remainderPrice()).isZero();
            assertThat(full.ownerAdvance()).isEqualTo(10_000_000L);       // owner part 100 000
            assertThat(full.joinSplit(BPS).platformReceives(BPS)).isEqualTo(2_500_000L);
        }
    }

    @Test
    void absentMeansMinimum() {
        assertThat(quote(20_000_000L, 5_000_000L, Side.A).choose(null).option()).isEqualTo(PaymentOption.MINIMUM);
    }

    @Test
    void aMinimumAtOrAboveTheFullNetOffersOnlyFull() {
        // P/2 + F = 5 000 000 + 2 500 000 = 7 500 000; M = 8 000 000 is above it.
        TeamPaymentQuote q = quote(10_000_000L, 8_000_000L, Side.A);
        assertThat(q.minimumOffered()).isFalse();
        assertThat(q.minimumPrice()).isEqualTo(q.fullPrice());
        assertThat(q.minimumRemainder()).isZero();
        TeamPaymentQuote.Choice choice = q.choose(PaymentOption.MINIMUM);
        assertThat(choice.option()).isEqualTo(PaymentOption.FULL);
        assertThat(choice.remainderPrice()).isZero();
    }

    @Test
    void aMinimumNotAboveTheFeeIsNeverOffered() {
        assertThat(quote(20_000_000L, FEE, Side.A).minimumOffered()).isFalse();
        assertThat(quote(20_000_000L, 1_000_000L, Side.A).minimumOffered()).isFalse();
    }

    @Test
    void anOddPriceGivesTheExtraTiyinToSideB() {
        TeamPaymentQuote a = quote(20_000_001L, 5_000_000L, Side.A);
        TeamPaymentQuote b = quote(20_000_001L, 5_000_000L, Side.B);
        assertThat(a.fullNet() - FEE + b.fullNet() - FEE).isEqualTo(20_000_001L);
        assertThat(b.fullNet() - a.fullNet()).isEqualTo(1L);
    }

    /** Property-style: many P (odd and even), M and settings. */
    @Test
    void theMoneyRulesHoldForManyPricesAndMinimums() {
        Random random = new Random(20261106L);
        long[] steps = {500_000L, 100_000L, 10_000L, 1_000L};
        int[] fees = {150, 100, 200, 0};
        for (int i = 0; i < 20_000; i++) {
            long price = 1_000_000L + random.nextLong(499_000_001L);       // 10 000 .. 5 000 000 so'm
            if (i % 2 == 0) {
                price |= 1L; // odd
            } else {
                price &= ~1L; // even
            }
            long fee = i % 5 == 0 ? 0L : 100_000L + random.nextLong(5_000_000L);
            long minimum = 50_000L + random.nextLong(price + fee + 1_000_000L);
            long step = steps[i % steps.length];
            int bps = fees[i % fees.length];

            long ownerNetFull = 0;
            for (Side side : Side.values()) {
                TeamPaymentQuote q = TeamPaymentQuote.of(price, minimum, fee, side, bps, step);
                assertThat(q.fullPrice() % step).isZero();
                assertThat(q.minimumPrice() % step).isZero();
                if (minimum <= fee) {
                    assertThat(q.minimumOffered()).as("M <= F refused").isFalse();
                }
                for (PaymentOption option : PaymentOption.values()) {
                    TeamPaymentQuote.Choice c = q.choose(option);
                    CardFeeSplit split = c.joinSplit(bps);
                    // The platform nets exactly F after ATMOS, whatever the option.
                    assertThat(split.platformReceives(bps)).isEqualTo(fee);
                    // Remainder = full - paid, never rounded on its own.
                    assertThat(c.remainderPrice()).isEqualTo(q.fullPrice() - c.joinPrice()).isNotNegative();
                    assertThat(c.joinPrice() % step).isZero();
                    // No double rounding: the join is playerAmount(net) exactly.
                    long net = c.fee() + c.ownerAdvance();
                    assertThat(c.joinPrice()).isEqualTo(PriceCalculator.playerAmount(net, bps, step));
                    assertThat(c.ownerAdvance() + c.ownerRemainder() + c.fee()).isEqualTo(q.fullNet());
                    if (c.option() == PaymentOption.MINIMUM) {
                        assertThat(net).isEqualTo(minimum);
                        assertThat(c.ownerAdvance()).isPositive();
                    }
                }
                ownerNetFull += q.choose(PaymentOption.FULL).joinSplit(bps).ownerReceives(bps);
            }
            // Both teams paying full: the owner nets at least the pitch price.
            assertThat(ownerNetFull).as("P=%d F=%d step=%d bps=%d", price, fee, step, bps)
                    .isGreaterThanOrEqualTo(price);
        }
    }
}
