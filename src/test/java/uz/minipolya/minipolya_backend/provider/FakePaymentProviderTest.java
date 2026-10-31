package uz.minipolya.minipolya_backend.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentProviderTest {

    private static final String UZCARD = "8600123412341234";
    private static final String HUMO = "9860123412341234";
    private static final String DECLINING = "8600123412340002";
    private static final String EXPIRY = "12/29";

    private final MovableClock clock = new MovableClock();
    private FakePaymentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FakePaymentProvider(new MockEnvironment(), clock);
    }

    private String bind(String cardNumber) {
        CardBindingStart start = provider.bindCardInit(cardNumber, EXPIRY);
        return provider.bindCardConfirm(start.bindingId(), FakePaymentProvider.EXPECTED_SMS_CODE).cardToken();
    }

    private ChargeRequest charge(String token, long owner, long platform) {
        return ChargeRequest.withToken(List.of(Split.owner("rcp-1", owner), Split.platform(platform)),
                token, UUID.randomUUID().toString());
    }

    private HoldRequest hold(String token, int minutes) {
        return new HoldRequest(List.of(Split.owner("rcp-1", 5_000_000L), Split.platform(2_500_000L)),
                token, UUID.randomUUID().toString(), minutes);
    }

    // ---- cards ----

    @Test
    void bindingNeedsTheSmsCodeAndGivesATokenAnIdAndAMaskedNumber() {
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);
        assertThat(start.maskedPhone()).startsWith("****");

        BoundCard card = provider.bindCardConfirm(start.bindingId(), "111111");

        assertThat(card.cardToken()).isNotBlank();
        assertThat(card.cardId()).isNotBlank();
        assertThat(card.maskedPan()).isEqualTo("8600 •••• 1234");
        assertThat(card.maskedPan()).doesNotContain("12341234");
        assertThat(card.toString()).doesNotContain(card.cardToken());
    }

    @Test
    void aWrongSmsCodeOrAnUnknownOrReusedBindingIsRefused() {
        CardBindingStart start = provider.bindCardInit(HUMO, EXPIRY);
        assertThatThrownBy(() -> provider.bindCardConfirm(start.bindingId(), "000000"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> provider.bindCardConfirm("no-such-binding", "111111"))
                .isInstanceOf(BadRequestException.class);
        provider.bindCardConfirm(start.bindingId(), "111111");
        assertThatThrownBy(() -> provider.bindCardConfirm(start.bindingId(), "111111"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aRemovedCardCanNoLongerBeCharged() {
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);
        BoundCard card = provider.bindCardConfirm(start.bindingId(), "111111");

        provider.removeCard(card.cardId(), card.cardToken());

        assertThatThrownBy(() -> provider.charge(charge(card.cardToken(), 100_000L, 1_000L)))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- charges ----

    @Test
    void aChargeIsOneTransactionPerSplitAndTheCommissionIs1Point5PercentOfEach() {
        String token = bind(UZCARD);

        PendingCharge pending = provider.charge(charge(token, 20_304_569L, 5_431L));
        assertThat(pending.transactionIds()).hasSize(2);
        assertThat(pending.otpRequired()).isFalse();

        ChargeOutcome outcome = provider.confirmCharge(pending.transactionIds(), null);

        assertThat(outcome.status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(outcome.successTransIds()).hasSize(2);
        assertThat(outcome.commission()).isEqualTo(20_304_569L * 150 / 10_000 + 5_431L * 150 / 10_000);
        assertThat(outcome.ofdUrl()).isNotBlank();
    }

    @Test
    void theSameAccountNeverCreatesASecondCharge() {
        String token = bind(UZCARD);
        ChargeRequest request = ChargeRequest.withToken(List.of(Split.owner("rcp-1", 100_000L)), token, "acc-1");

        PendingCharge first = provider.charge(request);
        PendingCharge retry = provider.charge(request);

        assertThat(retry.transactionIds()).isEqualTo(first.transactionIds());
    }

    @Test
    void aCardEndingIn0002IsDeclinedAsAnOutcomeNotAnException() {
        String token = bind(DECLINING);

        PendingCharge pending = provider.charge(charge(token, 100_000L, 10_000L));
        ChargeOutcome outcome = provider.confirmCharge(pending.transactionIds(), null);

        assertThat(outcome.status()).isEqualTo(ProviderStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(FakePaymentProvider.DECLINED);
        assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.FAILED);
    }

    @Test
    void aCardNumberChargeNeedsTheSmsCode() {
        ChargeRequest byNumber = new ChargeRequest(List.of(Split.owner("rcp-1", 100_000L)),
                null, UZCARD, EXPIRY, "acc-number");

        PendingCharge pending = provider.charge(byNumber);
        assertThat(pending.otpRequired()).isTrue();
        assertThatThrownBy(() -> provider.confirmCharge(pending.transactionIds(), "000000"))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(provider.confirmCharge(pending.transactionIds(), "111111").status())
                .isEqualTo(ProviderStatus.SUCCESS);
    }

    @Test
    void getChargeAnswersWhenAResponseWasLost() {
        String token = bind(UZCARD);
        PendingCharge pending = provider.charge(charge(token, 100_000L, 10_000L));

        assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.PENDING);
        provider.confirmCharge(pending.transactionIds(), null);
        assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.SUCCESS);
    }

    @Test
    void aConfirmedTransactionIsReversedOnceAndOnlyOnce() {
        String token = bind(UZCARD);
        PendingCharge pending = provider.charge(charge(token, 100_000L, 10_000L));
        String successId = provider.confirmCharge(pending.transactionIds(), null).successTransIds().getFirst();

        provider.reverse(successId, "cancelled");

        assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isNotEqualTo(ProviderStatus.SUCCESS);
        assertThatThrownBy(() -> provider.reverse(successId, "again"))
                .isInstanceOf(TransactionClosedException.class);
    }

    // ---- holds ----

    @Test
    void aHoldIsConfirmedThenCapturedInFull() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        assertThat(created.status()).isEqualTo(ProviderStatus.PENDING);
        assertThat(created.holdTill()).isEqualTo(clock.now().plusMinutes(60));

        assertThat(provider.confirmHold(created.holdId(), null).status()).isEqualTo(ProviderStatus.HELD);
        HoldOutcome captured = provider.captureHold(created.holdId());

        assertThat(captured.status()).isEqualTo(ProviderStatus.CAPTURED);
        assertThat(captured.commission()).isEqualTo(5_000_000L * 150 / 10_000 + 2_500_000L * 150 / 10_000);
    }

    @Test
    void aHoldOnADecliningCardFails() {
        String token = bind(DECLINING);
        HoldOutcome created = provider.hold(hold(token, 60));

        assertThat(provider.confirmHold(created.holdId(), null).failed()).isTrue();
    }

    @Test
    void aHoldCannotBeCapturedOnceItHasExpired() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        provider.confirmHold(created.holdId(), null);

        clock.advance(Duration.ofMinutes(61));

        assertThatThrownBy(() -> provider.captureHold(created.holdId()))
                .isInstanceOf(HoldExpiredException.class)
                .extracting("code").isEqualTo(FakePaymentProvider.HOLD_EXPIRED);
        // Nobody settled it, so the provider let it go.
        assertThat(provider.getHold(created.holdId()).status()).isEqualTo(ProviderStatus.RELEASED);
    }

    @Test
    void aReleasedHoldCannotBeCapturedOrReleasedAgain() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        provider.confirmHold(created.holdId(), null);

        assertThat(provider.releaseHold(created.holdId()).status()).isEqualTo(ProviderStatus.RELEASED);

        assertThatThrownBy(() -> provider.captureHold(created.holdId())).isInstanceOf(HoldCancelledException.class);
        assertThatThrownBy(() -> provider.releaseHold(created.holdId())).isInstanceOf(HoldCancelledException.class);
    }

    @Test
    void aCapturedHoldCannotBeReleased() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        provider.confirmHold(created.holdId(), null);
        provider.captureHold(created.holdId());

        assertThatThrownBy(() -> provider.releaseHold(created.holdId())).isInstanceOf(HoldCancelledException.class);
    }

    // ---- production ----

    @Test
    void itRefusesToStartUnderTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new FakePaymentProvider(prod, clock).refuseInProduction())
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new FakePaymentProvider(new MockEnvironment(), clock).refuseInProduction())
                .doesNotThrowAnyException();
    }

    /** A clock the test moves forward by hand. */
    private static final class MovableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-29T09:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        java.time.OffsetDateTime now() {
            return java.time.OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
