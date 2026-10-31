package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
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
    void aQuoteCarriesBothPricesAndTheirDifference() {
        CardPricing.Quote q = pricing.quote(20_000_000L);

        assertThat(q.price()).isEqualTo(20_310_000L);
        assertThat(q.netPrice()).isEqualTo(20_000_000L);
        assertThat(q.cardFee()).isEqualTo(310_000L);
    }

    @Test
    void aBookingSplitTakesTheCommissionOffTheOwnersNet() {
        CardPricing withCommission = new CardPricing(
                () -> PlatformSettings.builder().bookingCommissionPercent(10).build());

        var split = withCommission.bookingSplit(20_000_000L);

        assertThat(split.ownerNet()).isEqualTo(18_000_000L);
        assertThat(split.platformNet()).isEqualTo(2_000_000L);
        assertThat(split.playerAmount()).isEqualTo(20_310_000L);
        assertThat(split.ownerGross() + split.platformGross()).isEqualTo(split.playerAmount());
    }
}
