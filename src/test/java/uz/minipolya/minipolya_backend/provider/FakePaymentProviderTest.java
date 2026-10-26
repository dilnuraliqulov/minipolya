package uz.minipolya.minipolya_backend.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentProviderTest {

    private static final String UZCARD = "8600123412341234";
    private static final String HUMO = "9860999988881234";
    private static final String DECLINED = "8600123412340002";
    private static final String EXPIRY = "12/29";

    private FakePaymentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FakePaymentProvider(new MockEnvironment());
    }

    private String bindAndGetToken(String cardNumber) {
        CardBinding binding = provider.startCardBinding(cardNumber, EXPIRY);
        return provider.confirmCardBinding(binding.bindingId(), FakePaymentProvider.EXPECTED_SMS_CODE).token();
    }

    private ChargeCommand charge(String token, long amount) {
        return new ChargeCommand(token, amount, amount, 0L, "ref-" + amount);
    }

    // ---- card binding ----

    @Test
    void confirmingABindingReturnsATokenAndAMaskedNumber() {
        CardBinding binding = provider.startCardBinding(UZCARD, EXPIRY);
        assertThat(binding.bindingId()).isNotBlank();

        BoundCard card = provider.confirmCardBinding(binding.bindingId(), FakePaymentProvider.EXPECTED_SMS_CODE);

        assertThat(card.token()).isNotBlank();
        assertThat(card.maskedNumber()).isEqualTo("8600 •••• 1234");
        assertThat(card.cardType()).isEqualTo("uzcard");
    }

    @Test
    void maskedNumberNeverContainsTheMiddleDigits() {
        CardBinding binding = provider.startCardBinding(UZCARD, EXPIRY);

        BoundCard card = provider.confirmCardBinding(binding.bindingId(), FakePaymentProvider.EXPECTED_SMS_CODE);

        assertThat(card.maskedNumber()).doesNotContain("1234123412");
        assertThat(card.token()).doesNotContain(UZCARD);
    }

    @Test
    void detectsHumoFromItsPrefix() {
        CardBinding binding = provider.startCardBinding(HUMO, EXPIRY);

        BoundCard card = provider.confirmCardBinding(binding.bindingId(), FakePaymentProvider.EXPECTED_SMS_CODE);

        assertThat(card.cardType()).isEqualTo("humo");
        assertThat(card.maskedNumber()).isEqualTo("9860 •••• 1234");
    }

    @Test
    void rejectsAWrongSmsCode() {
        CardBinding binding = provider.startCardBinding(UZCARD, EXPIRY);

        assertThatThrownBy(() -> provider.confirmCardBinding(binding.bindingId(), "000000"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SMS code");
    }

    @Test
    void rejectsAnUnknownBindingId() {
        assertThatThrownBy(() -> provider.confirmCardBinding("no-such-binding",
                FakePaymentProvider.EXPECTED_SMS_CODE))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aBindingCannotBeConfirmedTwice() {
        CardBinding binding = provider.startCardBinding(UZCARD, EXPIRY);
        provider.confirmCardBinding(binding.bindingId(), FakePaymentProvider.EXPECTED_SMS_CODE);

        assertThatThrownBy(() -> provider.confirmCardBinding(binding.bindingId(),
                FakePaymentProvider.EXPECTED_SMS_CODE))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- charging ----

    @Test
    void chargeIsPaidAndCarriesAUniqueTransactionId() {
        String token = bindAndGetToken(UZCARD);

        ProviderTransaction first = provider.charge(charge(token, 50_000L));
        ProviderTransaction second = provider.charge(charge(token, 60_000L));

        assertThat(first.status()).isEqualTo(ProviderTransactionStatus.PAID);
        assertThat(first.providerTxnId()).isNotBlank();
        assertThat(second.providerTxnId()).isNotEqualTo(first.providerTxnId());
    }

    @Test
    void aCardEndingIn0002IsDeclined() {
        String token = bindAndGetToken(DECLINED);

        ProviderTransaction result = provider.charge(charge(token, 50_000L));

        assertThat(result.status()).isEqualTo(ProviderTransactionStatus.FAILED);
    }

    @Test
    void aDeclinedCardAlsoFailsOnHold() {
        String token = bindAndGetToken(DECLINED);

        assertThat(provider.hold(charge(token, 50_000L)).status())
                .isEqualTo(ProviderTransactionStatus.FAILED);
    }

    @Test
    void holdCanBeCaptured() {
        String token = bindAndGetToken(UZCARD);
        ProviderTransaction held = provider.hold(charge(token, 50_000L));
        assertThat(held.status()).isEqualTo(ProviderTransactionStatus.HELD);

        ProviderTransaction captured = provider.capture(held.providerTxnId());

        assertThat(captured.status()).isEqualTo(ProviderTransactionStatus.CAPTURED);
        assertThat(captured.providerTxnId()).isEqualTo(held.providerTxnId());
    }

    @Test
    void holdCanBeReleased() {
        String token = bindAndGetToken(UZCARD);
        ProviderTransaction held = provider.hold(charge(token, 50_000L));

        ProviderTransaction released = provider.release(held.providerTxnId());

        assertThat(released.status()).isEqualTo(ProviderTransactionStatus.RELEASED);
    }

    @Test
    void aCapturedHoldCannotBeReleased() {
        String token = bindAndGetToken(UZCARD);
        ProviderTransaction held = provider.hold(charge(token, 50_000L));
        provider.capture(held.providerTxnId());

        assertThatThrownBy(() -> provider.release(held.providerTxnId()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refundingAPaidChargeReturnsRefunded() {
        String token = bindAndGetToken(UZCARD);
        ProviderTransaction paid = provider.charge(charge(token, 50_000L));

        ProviderTransaction refunded = provider.refund(paid.providerTxnId());

        assertThat(refunded.status()).isEqualTo(ProviderTransactionStatus.REFUNDED);
    }

    @Test
    void refundingAnUnknownTransactionIsRejected() {
        assertThatThrownBy(() -> provider.refund("no-such-txn"))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- the prod guard ----

    @Test
    void refusesToStartUnderTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new FakePaymentProvider(prod).refuseInProduction())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    @Test
    void startsUnderANonProdProfile() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        assertThatCode(() -> new FakePaymentProvider(dev).refuseInProduction()).doesNotThrowAnyException();
    }
}
