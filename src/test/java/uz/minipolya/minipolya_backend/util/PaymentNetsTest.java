package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentNetsTest {

    private static final int BPS = 150;
    private static final long FEE = 2_500_000L;

    private static PaymentNets teamPayment(long amount) {
        CardFeeSplit split = CardFeeSplit.withPlatformFee(amount, FEE, BPS);
        return PaymentNets.ofCard(amount, split.ownerAmount(), split.platformAmount(), split.atmosFee(), BPS);
    }

    @ParameterizedTest
    @CsvSource({"5500000, 2917499, 82501", "13000000, 10304999, 195001"})
    void thePlatformKeepsExactlyTheTeamFee(long amount, long ownerNet, long atmosFee) {
        CardFeeSplit split = CardFeeSplit.withPlatformFee(amount, FEE, BPS);
        assertThat(split.platformAmount()).isEqualTo(2_538_072L);

        PaymentNets nets = teamPayment(amount);
        assertThat(nets.platformNet()).isEqualTo(FEE);
        assertThat(nets.ownerNet()).isEqualTo(ownerNet);
        assertThat(nets.atmosFee()).isEqualTo(atmosFee);
        assertThat(nets.ownerNet() + nets.platformNet() + nets.atmosFee()).isEqualTo(amount);
    }

    @Test
    void aStoredRowAtTheOldFeeKeepsItsOwnAmounts() {
        PaymentNets nets = PaymentNets.ofCard(5_500_000L, 3_063_451L, 2_436_549L, 82_501L, BPS);
        assertThat(nets.platformNet()).isEqualTo(2_400_000L);
        assertThat(nets.ownerNet()).isEqualTo(3_017_499L);
        assertThat(nets.atmosFee()).isEqualTo(82_501L);
    }

    @Test
    void aRefundMirrorsThePayment() {
        PaymentNets nets = PaymentNets.ofCard(-5_500_000L, -2_961_928L, -2_538_072L, -82_501L, BPS);
        assertThat(nets.platformNet()).isEqualTo(-FEE);
        assertThat(nets.ownerNet()).isEqualTo(-2_917_499L);
        assertThat(nets.atmosFee()).isEqualTo(-82_501L);
    }

    @Test
    void withoutAStoredFeeItIsWorkedOutPerSplit() {
        PaymentNets nets = PaymentNets.ofCard(5_500_000L, 2_961_928L, 2_538_072L, null, BPS);
        assertThat(nets.atmosFee()).isEqualTo(82_501L);
        assertThat(nets.ownerNet()).isEqualTo(2_917_499L);
    }

    @Test
    void cashIsAllTheOwnersAndATMOSTakesNothing() {
        assertThat(PaymentNets.ofCash(13_000_000L)).isEqualTo(new PaymentNets(13_000_000L, 0L, 0L));
    }
}
