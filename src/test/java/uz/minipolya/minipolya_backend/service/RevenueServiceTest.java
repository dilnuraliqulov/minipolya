package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.repository.RevenueRow;
import uz.minipolya.minipolya_backend.util.Revenue;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How one payment row becomes its part of the figures. The queries are in AdminPaymentControllerTest. */
class RevenueServiceTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-03-04T05:00:00Z");

    @Test
    void theReportedCommissionIsTheFee() {
        assertThat(RevenueService.feeOf(Payment.Provider.ATMOS, 20_310_000L, 299_000L, 150)).isEqualTo(299_000);
    }

    @Test
    void withoutAReportTheFeeIsEstimatedAtTheCurrentRate() {
        // 1.5% of 20 310 000 = 304 650
        assertThat(RevenueService.feeOf(Payment.Provider.ATMOS, 20_310_000L, null, 150)).isEqualTo(304_650);
    }

    @Test
    void aLegacyCheckoutRowCarriesNoFee() {
        assertThat(RevenueService.feeOf(Payment.Provider.PAYME, 20_000_000L, null, 150)).isZero();
    }

    @Test
    void aReversalGivesTheOriginalsFeeBack() {
        RevenueRow original = new RevenueRow(UUID.randomUUID(), null, Payment.PaymentType.BOOKING,
                Payment.Provider.ATMOS, 20_310_000, 20_304_569, 5_431, 304_650L, AT, AT, null, null, null, false);
        RevenueRow refund = new RevenueRow(UUID.randomUUID(), null, Payment.PaymentType.REFUND,
                Payment.Provider.ATMOS, -20_310_000, -20_304_569, -5_431, null, AT, AT,
                Payment.Provider.ATMOS, 20_310_000L, 304_650L, false);

        Revenue net = RevenueService.line(original, 150).plus(RevenueService.line(refund, 150));

        assertThat(net).isEqualTo(Revenue.ZERO);
    }

    @Test
    void aRefundMadeByHandLeavesATMOSItsFee() {
        RevenueRow original = new RevenueRow(UUID.randomUUID(), null, Payment.PaymentType.BOOKING,
                Payment.Provider.ATMOS, 20_310_000, 20_304_569, 5_431, 304_650L, AT, AT, null, null, null, false);
        RevenueRow byHand = new RevenueRow(UUID.randomUUID(), null, Payment.PaymentType.REFUND,
                Payment.Provider.ATMOS, -20_310_000, -20_304_569, -5_431, null, AT, AT,
                Payment.Provider.ATMOS, 20_310_000L, 304_650L, true);

        Revenue net = RevenueService.line(original, 150).plus(RevenueService.line(byHand, 150));

        // Nothing kept, but ATMOS's fee on the card transaction was paid — by the owner and the platform.
        assertThat(net.appRevenue()).isZero();
        assertThat(net.cardFees()).isEqualTo(304_650);
        assertThat(net.ownerPayouts() + net.platformRevenue()).isEqualTo(-304_650);
    }
}
