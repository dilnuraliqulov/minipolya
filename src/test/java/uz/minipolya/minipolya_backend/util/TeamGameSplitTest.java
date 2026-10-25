package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The team-vs-team money arithmetic. Every figure is tiyin (so'm × 100): the
 * two teams split the stadium minimum as an up-front owner advance, each pays
 * the platform fee on top, and what is left of the pitch price is the
 * remainder they settle later.
 */
class TeamGameSplitTest {

    @Test
    void splitsTheMinimumBetweenTheTwoSides() {
        // P = 200 000 so'm, M = 50 000 so'm, fee = 25 000 so'm
        TeamGameSplit split = TeamGameSplit.of(20_000_000L, 5_000_000L, 2_500_000L);

        assertThat(split.advanceFor(Side.A)).isEqualTo(2_500_000L);
        assertThat(split.advanceFor(Side.B)).isEqualTo(2_500_000L);
        assertThat(split.joinAmountFor(Side.A)).isEqualTo(5_000_000L);   // 50 000 so'm
        assertThat(split.joinAmountFor(Side.B)).isEqualTo(5_000_000L);
        assertThat(split.remainderFor(Side.A)).isEqualTo(7_500_000L);    // 75 000 so'm
        assertThat(split.remainderFor(Side.B)).isEqualTo(7_500_000L);
    }

    @Test
    void theSecondSideGetsTheExtraTiyinWhenTheBaseIsOdd() {
        TeamGameSplit split = TeamGameSplit.of(1001L, 501L, 0L);

        assertThat(split.advanceFor(Side.A)).isEqualTo(250L);
        assertThat(split.advanceFor(Side.B)).isEqualTo(251L);
        assertThat(split.advanceFor(Side.A) + split.advanceFor(Side.B)).isEqualTo(501L);
        assertThat(split.remainderFor(Side.A)).isEqualTo(250L);
        assertThat(split.remainderFor(Side.B)).isEqualTo(250L);
    }

    @Test
    void withoutAStadiumMinimumEachSideAdvancesHalfThePrice() {
        TeamGameSplit split = TeamGameSplit.of(20_000_000L, null, 2_500_000L);

        assertThat(split.advanceFor(Side.A)).isEqualTo(10_000_000L);
        assertThat(split.advanceFor(Side.B)).isEqualTo(10_000_000L);
        assertThat(split.remainderFor(Side.A)).isZero();
        assertThat(split.remainderFor(Side.B)).isZero();
    }

    @Test
    void splitClampsBaseToPriceWhenMinimumExceedsIt() {
        TeamGameSplit split = TeamGameSplit.of(10_000_000L, 90_000_000L, 0L);

        assertThat(split.advanceBase()).isEqualTo(10_000_000L);
        assertThat(split.remainderFor(Side.A)).isZero();
        assertThat(split.remainderFor(Side.B)).isZero();
    }

    @Test
    void theAdvancesAndRemaindersAlwaysAddUpToThePrice() {
        TeamGameSplit split = TeamGameSplit.of(13_333_333L, 4_444_445L, 2_500_000L);

        long total = split.advanceFor(Side.A) + split.advanceFor(Side.B)
                + split.remainderFor(Side.A) + split.remainderFor(Side.B);
        assertThat(total).isEqualTo(13_333_333L);
    }

    @Test
    void theFeeIsTheSameForBothSidesAndIsNotPartOfThePitchPrice() {
        TeamGameSplit split = TeamGameSplit.of(20_000_000L, 5_000_000L, 2_500_000L);

        assertThat(split.fee()).isEqualTo(2_500_000L);
        assertThat(split.joinAmountFor(Side.A)).isEqualTo(split.fee() + split.advanceFor(Side.A));
        assertThat(split.joinAmountFor(Side.B)).isEqualTo(split.fee() + split.advanceFor(Side.B));
    }
}
