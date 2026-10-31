package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The card fee arithmetic: PriceCalculator.playerAmount and CardFeeSplit. */
class CardFeeTest {

    private static final int BPS = 150;
    private static final long STEP = 10_000L;

    @Test
    void twoHundredThousandSomBecomes203100() {
        // 20 000 000 * 10000 / 9850 = 20 304 568.53 → 20 304 569 → up to 20 310 000.
        assertThat(PriceCalculator.playerAmount(20_000_000L, BPS, STEP)).isEqualTo(20_310_000L);
    }

    @Test
    void zeroStaysZero() {
        assertThat(PriceCalculator.playerAmount(0L, BPS, STEP)).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            // net, expected: grossed amounts that land exactly on a step stay there
            "985000, 1000000",     // 9 850 so'm * 10000/9850 = exactly 10 000 so'm
            "1970000, 2000000",
            "9850000, 10000000",
            // one tiyin more tips over to the next step
            "985001, 1010000",
    })
    void amountsOnARoundingStepStayOnIt(long net, long expected) {
        assertThat(PriceCalculator.playerAmount(net, BPS, STEP)).isEqualTo(expected);
    }

    @Test
    void theLargestRealisticAmountsDoNotOverflow() {
        // 100 billion so'm, far beyond any booking, still fits in a long.
        long net = 10_000_000_000_000L;
        long player = PriceCalculator.playerAmount(net, BPS, STEP);
        assertThat(player).isGreaterThan(net).isEqualTo(10_152_284_270_000L);
        assertThat(player % STEP).isZero();
    }

    @Test
    void anAbsurdAmountFailsLoudlyInsteadOfWrapping() {
        assertThatThrownBy(() -> PriceCalculator.playerAmount(Long.MAX_VALUE / 2, BPS, STEP))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void zeroBpsOnlyRounds() {
        assertThat(PriceCalculator.playerAmount(20_000_000L, 0, STEP)).isEqualTo(20_000_000L);
        assertThat(PriceCalculator.playerAmount(20_000_001L, 0, STEP)).isEqualTo(20_010_000L);
        assertThat(PriceCalculator.playerAmount(20_000_001L, 0, 1)).isEqualTo(20_000_001L);
    }

    @Test
    void negativeOrNonsenseInputIsRejected() {
        assertThatThrownBy(() -> PriceCalculator.playerAmount(-1, BPS, STEP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceCalculator.playerAmount(1, 10_000, STEP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceCalculator.playerAmount(1, BPS, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- The split ----

    @Test
    void anOrdinaryBookingSplitLeavesTheOwnerExactlyTheirPrice() {
        CardFeeSplit split = CardFeeSplit.forNet(20_000_000L, 0L, BPS, STEP);

        assertThat(split.playerAmount()).isEqualTo(20_310_000L);
        assertThat(split.ownerGross()).isEqualTo(20_304_569L);
        assertThat(split.platformGross()).isEqualTo(5_431L); // only the rounding remainder
        assertThat(split.ownerGross() + split.platformGross()).isEqualTo(split.playerAmount());
        assertThat(split.cardFee()).isEqualTo(310_000L);
        // After a 1.5% fee on the owner's split, the owner nets at least their price.
        assertThat(split.ownerGross() - split.ownerGross() * BPS / 10_000).isGreaterThanOrEqualTo(20_000_000L);
    }

    @Test
    void aTeamGameSplitGrossesUpTheAdvanceAndThePlatformFee() {
        long advance = 5_000_000L;      // 50 000 so'm to the owner
        long fee = 2_500_000L;          // 25 000 so'm to the platform
        CardFeeSplit split = CardFeeSplit.forNet(advance, fee, BPS, STEP);

        assertThat(split.playerAmount()).isEqualTo(PriceCalculator.playerAmount(advance + fee, BPS, STEP));
        assertThat(split.ownerGross()).isEqualTo(PriceCalculator.grossUp(advance, BPS));
        assertThat(split.ownerGross() + split.platformGross()).isEqualTo(split.playerAmount());
        // The platform's split, less the same fee, still covers its 25 000 so'm.
        long platformAfterFee = split.platformGross() - split.platformGross() * BPS / 10_000;
        assertThat(platformAfterFee).isGreaterThanOrEqualTo(fee);
    }

    @Test
    void aBookingPaidInFullUsesTheSumOfSlotPrices() {
        // Two slots of 150 000 and 180 000 so'm: the player pays the two prices they saw.
        long slotA = PriceCalculator.playerAmount(15_000_000L, BPS, STEP);
        long slotB = PriceCalculator.playerAmount(18_000_000L, BPS, STEP);
        CardFeeSplit split = CardFeeSplit.of(slotA + slotB, 33_000_000L, 0L, BPS);

        assertThat(split.playerAmount()).isEqualTo(slotA + slotB);
        assertThat(split.ownerGross() + split.platformGross()).isEqualTo(slotA + slotB);
        assertThat(split.platformGross()).isNotNegative();
    }

    @Test
    void aSplitThatDoesNotAddUpCannotExist() {
        assertThatThrownBy(() -> new CardFeeSplit(100, 100, 0, 200, 150, 40, 100))
                .isInstanceOf(IllegalStateException.class);
    }
}
