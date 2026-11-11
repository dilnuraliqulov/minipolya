package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.util.TeamGameSplit;

import static org.assertj.core.api.Assertions.assertThat;

/** E1/E2: player prices are rounded up to 5 000 so'm (500 000 tiyin) by default. */
class RoundingStepDefaultTest {

    private static final long STEP = 500_000L;
    private final CardPricing pricing = CardPricing.withDefaults();

    @Test
    void theDefaultStepIsFiveThousandSom() {
        assertThat(PlatformSettings.DEFAULT_PAYMENT_ROUNDING_STEP).isEqualTo(STEP);
        assertThat(PlatformSettings.builder().build().getPaymentRoundingStep()).isEqualTo(STEP);
    }

    @Test
    void ownerPriceDepositAndRemainderAreAllRound() {
        long price = pricing.playerAmount(15_000_000L);          // 150 000 so'm
        long deposit = pricing.deposit(5_000_000L, price);       // minimum 50 000 so'm
        Booking booking = Booking.builder().price(15_000_000L).cardPrice(price).paidAmount(deposit).build();

        assertThat(price).isEqualTo(15_500_000L);                // 155 000
        assertThat(deposit).isEqualTo(5_500_000L);               // 55 000
        assertThat(pricing.remainingForPlayer(booking)).isEqualTo(10_000_000L); // 100 000
    }

    @Test
    void everyPlayerAmountIsAMultipleOfTheStep() {
        for (long owner = 1_000_000L; owner <= 60_000_000L; owner += 123_457L) {
            long price = pricing.playerAmount(owner);
            long deposit = pricing.deposit(owner / 3, price);
            assertThat(price % STEP).isZero();
            assertThat(deposit % STEP).isZero();
            assertThat((price - deposit) % STEP).isZero();
        }
    }

    @Test
    void teamGameAmountsAreMultiplesOfTheStep() {
        long fee = PlatformSettings.DEFAULT_TEAM_GAME_PLATFORM_FEE;
        for (long pitch = 10_000_000L; pitch <= 60_000_000L; pitch += 1_234_567L) {
            for (Long minimum : new Long[] {null, 3_000_000L, 7_777_777L}) {
                TeamGameSplit split = TeamGameSplit.of(pitch, minimum, fee);
                for (var side : uz.minipolya.minipolya_backend.entity.GameParticipant.Side.values()) {
                    assertThat(pricing.teamJoinPrice(split.advanceFor(side), split.fee()) % STEP).isZero();
                    assertThat(pricing.teamRemainderPrice(split.remainderFor(side)) % STEP).isZero();
                }
            }
        }
    }
}
