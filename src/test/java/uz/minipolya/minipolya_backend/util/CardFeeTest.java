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
    void anOrdinaryBookingIsAllTheOwners() {
        // 150 000 so'm at a 5 000 so'm step: the player pays 155 000, the owner receives 152 675.
        long player = PriceCalculator.playerAmount(15_000_000L, BPS, 500_000L);
        CardFeeSplit split = CardFeeSplit.toOwner(player, BPS);

        assertThat(split.playerAmount()).isEqualTo(15_500_000L);
        assertThat(split.ownerAmount()).isEqualTo(15_500_000L);
        assertThat(split.platformAmount()).isZero();
        assertThat(split.ownerReceives(BPS)).isEqualTo(15_267_500L);
        assertThat(split.atmosFee()).isEqualTo(232_500L);
    }

    @Test
    void aTeamGameSplitGivesThePlatformExactlyItsFee() {
        long advance = 5_000_000L;      // 50 000 so'm to the owner
        long fee = 2_500_000L;          // 25 000 so'm to the platform
        long player = PriceCalculator.playerAmount(advance + fee, BPS, STEP);
        CardFeeSplit split = CardFeeSplit.withPlatformFee(player, fee, BPS);

        assertThat(split.platformAmount()).isEqualTo(PriceCalculator.grossUp(fee, BPS));
        assertThat(split.ownerAmount() + split.platformAmount()).isEqualTo(player);
        assertThat(split.platformReceives(BPS)).isEqualTo(fee);
        assertThat(split.ownerReceives(BPS)).isGreaterThanOrEqualTo(advance - 1);
    }

    @Test
    void aZeroPlatformFeeIsAnOrdinarySplit() {
        assertThat(CardFeeSplit.withPlatformFee(1_000_000L, 0L, BPS))
                .isEqualTo(CardFeeSplit.toOwner(1_000_000L, BPS));
    }

    @Test
    void netAfterFeeUndoesGrossUp() {
        for (long net = 0; net < 5_000_000L; net += 997L) {
            assertThat(PriceCalculator.netAfterFee(PriceCalculator.grossUp(net, BPS), BPS)).isEqualTo(net);
        }
    }

    @Test
    void aSplitThatDoesNotAddUpOrIsNegativeCannotExist() {
        assertThatThrownBy(() -> new CardFeeSplit(200, 150, 40, 3))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CardFeeSplit(100, 110, -10, 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
