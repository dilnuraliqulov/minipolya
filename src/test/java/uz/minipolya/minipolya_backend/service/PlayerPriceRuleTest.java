package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.util.CardFeeSplit;
import uz.minipolya.minipolya_backend.util.TeamGameSplit;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-player-price rule, over many prices: owner prices 50 000 – 1 000 000 so'm in
 * steps of 1 000 so'm, with and without a minimum booking amount, rounding steps of 100
 * and 5 000 so'm, and a card fee of 150 bps and of 0. All figures tiyin.
 */
class PlayerPriceRuleTest {

    private static final long SOM = 100L;
    private static final long TEAM_FEE = 25_000 * SOM;
    private static final int[] FEES = {150, 0};
    private static final long[] STEPS = {100 * SOM, 5_000 * SOM};

    private static CardPricing pricing(int bps, long step) {
        PlatformSettings settings = PlatformSettings.builder()
                .cardFeeBps(bps).paymentRoundingStep(step).build();
        return new CardPricing(() -> settings);
    }

    /** 50 000 – 1 000 000 so'm in 1 000 so'm steps. */
    private static List<Long> ownerPrices() {
        List<Long> prices = new ArrayList<>();
        for (long som = 50_000; som <= 1_000_000; som += 1_000) {
            prices.add(som * SOM);
        }
        return prices;
    }

    // ---- The table examples ----

    @ParameterizedTest
    @CsvSource({
            "150000, 5000, 155000",
            "160000, 5000, 165000",
            "197000, 5000, 200000",
            "200000, 5000, 205000",
            "150000, 100, 152300",
            "130000, 100, 132000",   // Chilonzor Arena, default settings
            "130000, 5000, 135000",  // Chilonzor Arena, 5 000 so'm step
    })
    void playersSeeOnePrice(long ownerSom, long stepSom, long playerSom) {
        assertThat(pricing(150, stepSom * SOM).playerAmount(ownerSom * SOM)).isEqualTo(playerSom * SOM);
    }

    @Test
    void theDepositExample() {
        // A 50 000 so'm minimum is a 55 000 so'm deposit at a 5 000 so'm step.
        assertThat(pricing(150, 5_000 * SOM).deposit(50_000 * SOM, 155_000 * SOM)).isEqualTo(55_000 * SOM);
    }

    @Test
    void theOwnerReceivesExample() {
        // Owner price 150 000 → the player pays 155 000 → the owner receives 152 675.
        CardPricing p = pricing(150, 5_000 * SOM);
        CardFeeSplit charge = p.toOwner(p.playerAmount(150_000 * SOM));

        assertThat(charge.playerAmount()).isEqualTo(155_000 * SOM);
        assertThat(charge.platformAmount()).isZero();
        assertThat(charge.ownerReceives(150)).isEqualTo(152_675 * SOM);
    }

    // ---- Properties ----

    @Test
    void ordinaryBookings() {
        for (int bps : FEES) {
            for (long step : STEPS) {
                CardPricing p = pricing(bps, step);
                for (long n : ownerPrices()) {
                    // Two hours at different prices: what is shown per slot adds up to D.
                    long second = n + (n * 7 % (300_000 * SOM)) / (1_000 * SOM) * (1_000 * SOM);
                    long[] slots = {n, second};
                    long ownerTotal = n + second;
                    long d = 0;
                    for (long slot : slots) {
                        d += p.playerAmount(slot);
                    }
                    assertThat(d).as("D >= N").isGreaterThanOrEqualTo(ownerTotal);

                    for (Long minimum : minimums(n)) {
                        long deposit = minimum == null ? d : p.deposit(minimum, d);
                        long rest = d - deposit;
                        assertThat(deposit + rest).isEqualTo(d);
                        assertThat(deposit).isPositive();
                        assertThat(rest).isNotNegative();

                        CardFeeSplit first = p.toOwner(deposit);
                        CardFeeSplit last = p.toOwner(rest);
                        for (CardFeeSplit charge : List.of(first, last)) {
                            assertThat(charge.ownerAmount()).isNotNegative();
                            assertThat(charge.platformAmount()).as("the platform takes nothing").isZero();
                            assertThat(charge.atmosFee()).isNotNegative();
                        }
                        assertThat(first.ownerReceives(bps) + last.ownerReceives(bps))
                                .as("owner %d, step %d, bps %d, minimum %s", n, step, bps, minimum)
                                .isGreaterThanOrEqualTo(ownerTotal);
                    }
                }
            }
        }
    }

    @Test
    void teamGames() {
        for (int bps : FEES) {
            for (long step : STEPS) {
                CardPricing p = pricing(bps, step);
                for (long n : ownerPrices()) {
                    for (Long minimum : minimums(n)) {
                        TeamGameSplit split = TeamGameSplit.of(n, minimum, TEAM_FEE);
                        for (Side side : Side.values()) {
                            long advance = split.advanceFor(side);
                            long remainder = split.remainderFor(side);
                            CardFeeSplit join = CardFeeSplit.withPlatformFee(
                                    p.teamJoinPrice(advance, TEAM_FEE), TEAM_FEE, bps);
                            CardFeeSplit rest = p.toOwner(p.teamRemainderPrice(remainder));

                            for (CardFeeSplit charge : List.of(join, rest)) {
                                assertThat(charge.ownerAmount()).isNotNegative();
                                assertThat(charge.platformAmount()).isNotNegative();
                            }
                            assertThat(join.platformReceives(bps)).as("the platform nets exactly its fee")
                                    .isEqualTo(TEAM_FEE);
                            assertThat(rest.platformAmount()).isZero();
                            // ATMOS's fee is rounded per split, so the owner can come out one
                            // tiyin short of the advance when the player price lands exactly
                            // on the gross amount; never more.
                            assertThat(join.ownerReceives(bps) + rest.ownerReceives(bps))
                                    .isGreaterThanOrEqualTo(advance + remainder - 1);
                        }
                    }
                }
            }
        }
    }

    @Test
    void friendsGames() {
        for (int bps : FEES) {
            for (long step : STEPS) {
                CardPricing p = pricing(bps, step);
                for (long n : ownerPrices()) {
                    for (long serviceFee : new long[]{0, 5_000 * SOM}) {
                        Game game = Game.builder().totalPrice(n).maxPlayers(10).serviceFeePerPerson(serviceFee).build();
                        CardFeeSplit share = p.gameShareSplit(game);
                        assertThat(share.ownerAmount()).isNotNegative();
                        assertThat(share.platformAmount()).isNotNegative();
                        if (serviceFee == 0) {
                            assertThat(share.platformAmount()).isZero();
                            assertThat(share.ownerReceives(bps)).isGreaterThanOrEqualTo(n / 10);
                        } else {
                            assertThat(share.platformReceives(bps)).isEqualTo(serviceFee);
                        }
                    }
                }
            }
        }
    }

    /** No minimum, a small one, one inside the price, and one above it. */
    private static List<Long> minimums(long n) {
        List<Long> m = new ArrayList<>();
        m.add(null);
        m.add(50_000 * SOM);
        m.add(n / 3);
        m.add(n * 2);
        return m;
    }
}
