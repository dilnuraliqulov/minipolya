package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;

import static org.assertj.core.api.Assertions.assertThat;

class CardPricingTest {

    private final CardPricing pricing = CardPricing.withDefaults();

    @Test
    void maxNetForIsTheLargestNetWhoseCardPriceFits() {
        for (long cardPrice = 0; cardPrice <= 50_000_000L; cardPrice += 7_777L) {
            long net = pricing.maxNetFor(cardPrice);
            assertThat(pricing.playerAmount(net)).isLessThanOrEqualTo(cardPrice);
            assertThat(pricing.playerAmount(net + 1)).isGreaterThan(cardPrice);
        }
    }

    @Test
    void theDepositIsTheMinimumAsPlayersPayItButNeverMoreThanTheBooking() {
        CardPricing coarse = new CardPricing(() -> PlatformSettings.builder().paymentRoundingStep(500_000L).build());

        assertThat(coarse.deposit(5_000_000L, 15_500_000L)).isEqualTo(5_500_000L);   // 50 000 → 55 000
        assertThat(coarse.deposit(20_000_000L, 15_500_000L)).isEqualTo(15_500_000L); // capped at D
    }

    @Test
    void aBookingChargeIsAllTheOwners() {
        var split = pricing.toOwner(15_230_000L);

        assertThat(split.ownerAmount()).isEqualTo(15_230_000L);
        assertThat(split.platformAmount()).isZero();
    }

    @Test
    void aWalkInIsPricedAtTheOwnersOwnPrice() {
        Booking walkIn = Booking.builder().price(15_000_000L).isWalkin(true).build();
        Booking app = Booking.builder().price(15_000_000L).cardPrice(15_230_000L).build();

        assertThat(pricing.playerPrice(walkIn)).isEqualTo(15_000_000L);
        assertThat(pricing.playerPrice(app)).isEqualTo(15_230_000L);
    }
}
