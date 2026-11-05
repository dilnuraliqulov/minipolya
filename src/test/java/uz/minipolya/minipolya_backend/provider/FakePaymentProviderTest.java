package uz.minipolya.minipolya_backend.provider;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.provider.fake.FakeOperation;
import uz.minipolya.minipolya_backend.provider.fake.FakeReverse;
import uz.minipolya.minipolya_backend.provider.fake.FakeSplit;
import uz.minipolya.minipolya_backend.provider.fake.InMemoryFakeProviderStore;

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
    /** The clock below is 2026-10-29. */
    private static final String EXPIRY = "12/29";

    private final MovableClock clock = new MovableClock();
    private final AppProperties properties = new AppProperties();
    private final InMemoryFakeProviderStore store = new InMemoryFakeProviderStore();
    private FakePaymentProvider provider;

    @BeforeEach
    void setUp() {
        properties.getPayments().getFake().setDelayMinMs(0);
        properties.getPayments().getFake().setDelayMaxMs(0);
        provider = newProvider(new MockEnvironment());
    }

    private FakePaymentProvider newProvider(MockEnvironment environment) {
        return new FakePaymentProvider(environment, clock, properties, store, null);
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

    private static void refusedWith(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(PaymentProviderException.class).extracting("code").isEqualTo(code);
    }

    // ---- binding ----

    @Test
    void anyUzcardOrHumoNumberBindsWithAnySixDigitCode() {
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);
        assertThat(start.bindingId()).isNotBlank();
        assertThat(start.maskedPhone()).isEqualTo("+998 ** *** ** **");

        BoundCard card = provider.bindCardConfirm(start.bindingId(), "483920");

        assertThat(card.cardToken()).isNotBlank();
        assertThat(card.cardId()).isNotBlank();
        assertThat(card.maskedPan()).isEqualTo("8600 •••• •••• 1234");
        assertThat(card.expiry()).isEqualTo(EXPIRY);
        assertThat(card.cardHolder()).isNotBlank();
        assertThat(card.toString()).doesNotContain(card.cardToken());

        BoundCard humo = provider.bindCardConfirm(provider.bindCardInit(HUMO, "11/26").bindingId(), "000001");
        assertThat(humo.maskedPan()).isEqualTo("9860 •••• •••• 1234");
        assertThat(CardMask.type(humo.maskedPan())).isEqualTo("humo");
    }

    @Test
    void onlySixteenDigitUzcardAndHumoNumbersAreAccepted() {
        refusedWith(() -> provider.bindCardInit("4111111111111111", EXPIRY), AtmosCodes.WRONG_CARD_NUMBER);
        refusedWith(() -> provider.bindCardInit("5614681234567890", EXPIRY), AtmosCodes.WRONG_CARD_NUMBER);
        refusedWith(() -> provider.bindCardInit("860012341234123", EXPIRY), AtmosCodes.WRONG_CARD_NUMBER);
        // No Luhn check: any 16 digits after the prefix will do.
        assertThat(provider.bindCardInit("8600000000000000", EXPIRY).bindingId()).isNotBlank();
    }

    @Test
    void theExpiryMustBeARealMonthThatHasNotPassed() {
        refusedWith(() -> provider.bindCardInit(UZCARD, "13/29"), AtmosCodes.WRONG_EXPIRY);
        refusedWith(() -> provider.bindCardInit(UZCARD, "1229"), AtmosCodes.WRONG_EXPIRY);
        refusedWith(() -> provider.bindCardInit(UZCARD, "09/26"), AtmosCodes.CARD_EXPIRED);
        // The current month is still valid.
        assertThat(provider.bindCardInit(UZCARD, "10/26").bindingId()).isNotBlank();
    }

    @Test
    void reservedCodesAreWrongOrExpiredAndLeaveTheBindingOpen() {
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);

        refusedWith(() -> provider.bindCardConfirm(start.bindingId(), FakePaymentProvider.WRONG_CODE), AtmosCodes.WRONG_OTP);
        refusedWith(() -> provider.bindCardConfirm(start.bindingId(), FakePaymentProvider.EXPIRED_CODE), AtmosCodes.OTP_EXPIRED);
        refusedWith(() -> provider.bindCardConfirm(start.bindingId(), "12345"), AtmosCodes.WRONG_OTP);
        refusedWith(() -> provider.bindCardConfirm(start.bindingId(), "12a456"), AtmosCodes.WRONG_OTP);

        assertThat(provider.bindCardConfirm(start.bindingId(), "123456").cardToken()).isNotBlank();
    }

    @Test
    void theCodeLengthIsConfigurable() {
        properties.getPayments().getFake().setOtpLength(4);
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);

        refusedWith(() -> provider.bindCardConfirm(start.bindingId(), "123456"), AtmosCodes.WRONG_OTP);
        assertThat(provider.bindCardConfirm(start.bindingId(), "1234").cardToken()).isNotBlank();
    }

    @Test
    void aCodeExpiresAfterItsLifetime() {
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);
        clock.advance(Duration.ofSeconds(181));

        refusedWith(() -> provider.bindCardConfirm(start.bindingId(), "123456"), AtmosCodes.OTP_EXPIRED);
    }

    @Test
    void aCodeCanBeResentOnlyAfterTheCooldownAndTheNewOneWorks() {
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);

        clock.advance(Duration.ofSeconds(59));
        refusedWith(() -> provider.resendBindingCode(start.bindingId()), AtmosCodes.OTP_RESEND_TOO_EARLY);

        clock.advance(Duration.ofSeconds(1));
        CardBindingStart again = provider.resendBindingCode(start.bindingId());
        assertThat(again.bindingId()).isEqualTo(start.bindingId());
        refusedWith(() -> provider.resendBindingCode(start.bindingId()), AtmosCodes.OTP_RESEND_TOO_EARLY);

        // The lifetime runs from the latest code.
        clock.advance(Duration.ofSeconds(170));
        assertThat(provider.bindCardConfirm(start.bindingId(), "123456").cardToken()).isNotBlank();
    }

    @Test
    void anUnknownOrAlreadyConfirmedBindingIsRefused() {
        refusedWith(() -> provider.bindCardConfirm("no-such-binding", "123456"), AtmosCodes.BINDING_NOT_FOUND);
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);
        provider.bindCardConfirm(start.bindingId(), "123456");

        refusedWith(() -> provider.bindCardConfirm(start.bindingId(), "123456"), AtmosCodes.BINDING_NOT_FOUND);
        refusedWith(() -> provider.resendBindingCode(start.bindingId()), AtmosCodes.BINDING_NOT_FOUND);
    }

    @Test
    void aRemovedCardsTokenNoLongerWorks() {
        CardBindingStart start = provider.bindCardInit(UZCARD, EXPIRY);
        BoundCard card = provider.bindCardConfirm(start.bindingId(), "123456");

        provider.removeCard(card.cardId(), card.cardToken());

        refusedWith(() -> provider.charge(charge(card.cardToken(), 100_000L, 100_000L)), AtmosCodes.CARD_NOT_FOUND);
        refusedWith(() -> provider.removeCard(card.cardId(), card.cardToken()), AtmosCodes.CARD_NOT_FOUND);
    }

    // ---- charges ----

    private String bindEnding(String last4) {
        return bind("860012341234" + last4);
    }

    private long balanceOf(String token) {
        return store.cardByTokenHash(FakePaymentProvider.sha256(token)).orElseThrow().getBalance();
    }

    private ChargeOutcome pay(String token, long owner, long platform) {
        PendingCharge pending = provider.charge(charge(token, owner, platform));
        return provider.confirmCharge(pending.transactionIds(), null);
    }

    @Test
    void aChargeIsOneTransactionPerSplitAndAtmosKeeps1Point5PercentOfEach() {
        String token = bind(UZCARD);

        PendingCharge pending = provider.charge(charge(token, 20_304_569L, 2_538_100L));
        assertThat(pending.transactionIds()).hasSize(2);
        assertThat(pending.otpRequired()).isFalse();

        ChargeOutcome outcome = provider.confirmCharge(pending.transactionIds(), null);

        assertThat(outcome.status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(outcome.successTransIds()).hasSize(2);
        // Per split, rounded so the recipient keeps exactly netAfterFee: 304 569 + 38 072.
        long owner = 20_304_569L - uz.minipolya.minipolya_backend.util.PriceCalculator.netAfterFee(20_304_569L, 150);
        long platform = 2_538_100L - uz.minipolya.minipolya_backend.util.PriceCalculator.netAfterFee(2_538_100L, 150);
        assertThat(outcome.commission()).isEqualTo(owner + platform).isEqualTo(304_569L + 38_072L);
        assertThat(outcome.ofdUrl()).isNotBlank();
        // The platform's split of a team fee nets exactly the fee.
        assertThat(2_538_100L - platform).isEqualTo(2_500_028L);

        FakeOperation recorded = store.operationByTransaction(pending.transactionIds().getFirst()).orElseThrow();
        assertThat(recorded.getSplits()).extracting(FakeSplit::isPlatform).containsExactly(false, true);
        assertThat(recorded.getSplits()).extracting(FakeSplit::getRecipientId).containsExactly("rcp-1", null);
    }

    @Test
    void aChargeTakesFromTheBalanceAndAReverseGivesItBack() {
        String token = bind(UZCARD);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);

        ChargeOutcome paid = pay(token, 15_500_000L, 100_000L);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L - 15_600_000L);

        paid.successTransIds().forEach(id -> provider.reverse(id, "cancelled"));
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);
    }

    @Test
    void notEnoughOnTheCardIsADeclineNotAnException() {
        properties.getPayments().getFake().setStartingBalance(10_000_000L);
        String token = bind(UZCARD);

        ChargeOutcome outcome = pay(token, 9_000_000L, 2_000_000L);

        assertThat(outcome.status()).isEqualTo(ProviderStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(AtmosCodes.INSUFFICIENT_BALANCE);
        assertThat(balanceOf(token)).isEqualTo(10_000_000L);
    }

    @Test
    void everySplitMustBeAtLeast500Som() {
        String token = bind(UZCARD);

        refusedWith(() -> provider.charge(charge(token, 100_000L, 49_999L)), AtmosCodes.WRONG_AMOUNT);
        assertThat(pay(token, 100_000L, 50_000L).status()).isEqualTo(ProviderStatus.SUCCESS);
    }

    @Test
    void theSameAccountNeverChargesTwice() {
        String token = bind(UZCARD);
        ChargeRequest request = ChargeRequest.withToken(List.of(Split.owner("rcp-1", 100_000L)), token, "acc-1");

        PendingCharge first = provider.charge(request);
        ChargeOutcome paid = provider.confirmCharge(first.transactionIds(), null);
        PendingCharge retry = provider.charge(request);
        ChargeOutcome again = provider.confirmCharge(retry.transactionIds(), null);

        assertThat(retry.transactionIds()).isEqualTo(first.transactionIds());
        assertThat(again).isEqualTo(paid);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L - 100_000L);
    }

    @Test
    void endings0002And0051And0054AreDeclinesWithTheirCodes() {
        for (String[] scenario : new String[][]{
                {FakePaymentProvider.DECLINED_SUFFIX, AtmosCodes.CARD_BLOCKED},
                {FakePaymentProvider.INSUFFICIENT_SUFFIX, AtmosCodes.INSUFFICIENT_FUNDS},
                {FakePaymentProvider.EXPIRED_SUFFIX, AtmosCodes.CARD_EXPIRED}}) {
            String token = bindEnding(scenario[0]);
            PendingCharge pending = provider.charge(charge(token, 100_000L, 100_000L));

            ChargeOutcome outcome = provider.confirmCharge(pending.transactionIds(), null);

            assertThat(outcome.status()).as(scenario[0]).isEqualTo(ProviderStatus.FAILED);
            assertThat(outcome.errorCode()).as(scenario[0]).isEqualTo(scenario[1]);
            assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.FAILED);
            assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);
        }
    }

    @Test
    void ending0091TimesOutButTheMoneyWasTaken() {
        String token = bindEnding(FakePaymentProvider.TIMEOUT_THEN_SUCCESS_SUFFIX);
        PendingCharge pending = provider.charge(charge(token, 100_000L, 100_000L));

        assertThatThrownBy(() -> provider.confirmCharge(pending.transactionIds(), null))
                .isInstanceOf(ProviderTimeoutException.class);

        ChargeOutcome status = provider.getCharge(pending.transactionIds().getFirst());
        assertThat(status.status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(status.successTransIds()).hasSize(1);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L - 200_000L);
    }

    @Test
    void ending0092TimesOutAndNothingWasTaken() {
        String token = bindEnding(FakePaymentProvider.TIMEOUT_THEN_FAILED_SUFFIX);
        PendingCharge pending = provider.charge(charge(token, 100_000L, 100_000L));

        assertThatThrownBy(() -> provider.confirmCharge(pending.transactionIds(), null))
                .isInstanceOf(ProviderTimeoutException.class);

        assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.FAILED);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);
    }

    @Test
    void ending0099IsProviderUnavailableForChargesAndHolds() {
        String token = bindEnding(FakePaymentProvider.UNAVAILABLE_SUFFIX);

        refusedWith(() -> provider.charge(charge(token, 100_000L, 100_000L)), AtmosCodes.SERVICE_UNAVAILABLE);
        refusedWith(() -> provider.hold(hold(token, 60)), AtmosCodes.SERVICE_UNAVAILABLE);
        assertThat(AtmosCodes.isUnavailable(AtmosCodes.SERVICE_UNAVAILABLE)).isTrue();
    }

    @Test
    void ending0098ChargesButEveryReverseIsRefused() {
        String token = bindEnding(FakePaymentProvider.REVERSE_FAILS_SUFFIX);
        ChargeOutcome paid = pay(token, 100_000L, 100_000L);
        assertThat(paid.status()).isEqualTo(ProviderStatus.SUCCESS);

        String id = paid.successTransIds().getFirst();
        refusedWith(() -> provider.reverse(id, "cancelled"), AtmosCodes.NOT_CANCELLED);
        refusedWith(() -> provider.reverse(id, "retried"), AtmosCodes.NOT_CANCELLED);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L - 200_000L);
        assertThat(store.latestReverses(10)).extracting(FakeReverse::getOutcome)
                .containsOnly(FakeReverse.Outcome.REFUSED);
    }

    @Test
    void aCardNumberChargeNeedsAValidSmsCode() {
        ChargeRequest byNumber = new ChargeRequest(List.of(Split.owner("rcp-1", 100_000L)),
                null, UZCARD, EXPIRY, "acc-number");

        PendingCharge pending = provider.charge(byNumber);
        assertThat(pending.otpRequired()).isTrue();
        refusedWith(() -> provider.confirmCharge(pending.transactionIds(), FakePaymentProvider.WRONG_CODE), AtmosCodes.WRONG_OTP);

        assertThat(provider.confirmCharge(pending.transactionIds(), "654321").status()).isEqualTo(ProviderStatus.SUCCESS);
    }

    @Test
    void getChargeAnswersWhenAResponseWasLost() {
        String token = bind(UZCARD);
        PendingCharge pending = provider.charge(charge(token, 100_000L, 100_000L));

        assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.PENDING);
        provider.confirmCharge(pending.transactionIds(), null);
        assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.SUCCESS);
        refusedWith(() -> provider.getCharge("txn-unknown"), FakePaymentProvider.NOT_FOUND);
    }

    // ---- reverses ----

    @Test
    void aTransactionIsReversedInFullOnceAndOnlyOnce() {
        String token = bind(UZCARD);
        PendingCharge pending = provider.charge(charge(token, 100_000L, 100_000L));
        String successId = provider.confirmCharge(pending.transactionIds(), null).successTransIds().getFirst();

        provider.reverse(successId, "cancelled");

        assertThat(provider.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.REVERSED);
        assertThatThrownBy(() -> provider.reverse(successId, "again"))
                .isInstanceOf(TransactionClosedException.class)
                .extracting("code").isEqualTo(AtmosCodes.ALREADY_REVERSED);
        // Only the reversed split came back.
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L - 100_000L);
    }

    @Test
    void aReverseIsRefusedAfterTheRefundWindow() {
        String token = bind(UZCARD);
        String successId = pay(token, 100_000L, 100_000L).successTransIds().getFirst();

        clock.advance(Duration.ofDays(93));

        refusedWith(() -> provider.reverse(successId, "too late"), AtmosCodes.PAYMENT_CLOSED);
    }

    // ---- holds ----

    @Test
    void aHoldIsConfirmedThenCapturedInFull() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        assertThat(created.status()).isEqualTo(ProviderStatus.PENDING);
        assertThat(created.holdTill()).isEqualTo(clock.now().plusMinutes(60));

        assertThat(provider.confirmHold(created.holdId(), null).status()).isEqualTo(ProviderStatus.HELD);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L - 7_500_000L);
        HoldOutcome captured = provider.captureHold(created.holdId());

        assertThat(captured.status()).isEqualTo(ProviderStatus.CAPTURED);
        assertThat(captured.commission()).isEqualTo(75_000L + 37_500L);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L - 7_500_000L);
    }

    @Test
    void theSameAccountNeverHoldsTwice() {
        String token = bind(UZCARD);
        HoldRequest request = hold(token, 60);

        HoldOutcome first = provider.hold(request);
        provider.confirmHold(first.holdId(), null);
        HoldOutcome retry = provider.hold(request);

        assertThat(retry.holdId()).isEqualTo(first.holdId());
        assertThat(retry.status()).isEqualTo(ProviderStatus.HELD);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L - 7_500_000L);
    }

    @Test
    void aHoldOnADecliningCardFailsWithItsCode() {
        HoldOutcome created = provider.hold(hold(bindEnding(FakePaymentProvider.INSUFFICIENT_SUFFIX), 60));

        HoldOutcome confirmed = provider.confirmHold(created.holdId(), null);

        assertThat(confirmed.failed()).isTrue();
        assertThat(confirmed.errorCode()).isEqualTo(AtmosCodes.INSUFFICIENT_FUNDS);
    }

    @Test
    void aHoldTimesOutOn0091ButIsHeldWhenAskedAgain() {
        HoldOutcome created = provider.hold(hold(bindEnding(FakePaymentProvider.TIMEOUT_THEN_SUCCESS_SUFFIX), 60));

        assertThatThrownBy(() -> provider.confirmHold(created.holdId(), null)).isInstanceOf(ProviderTimeoutException.class);

        assertThat(provider.getHold(created.holdId()).status()).isEqualTo(ProviderStatus.HELD);
    }

    @Test
    void aHoldLapsesOnItsOwnAndCannotBeCapturedThen() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        provider.confirmHold(created.holdId(), null);

        clock.advance(Duration.ofMinutes(60));

        assertThatThrownBy(() -> provider.captureHold(created.holdId()))
                .isInstanceOf(HoldExpiredException.class)
                .extracting("code").isEqualTo(AtmosCodes.HOLD_EXPIRED);
        HoldOutcome now = provider.getHold(created.holdId());
        assertThat(now.status()).isEqualTo(ProviderStatus.RELEASED);
        assertThat(now.errorCode()).isEqualTo(AtmosCodes.HOLD_EXPIRED);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);
        // Asking again changes nothing.
        assertThatThrownBy(() -> provider.captureHold(created.holdId())).isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void ending0097HoldsButTheCaptureIsRefusedAndTheHoldCancelled() {
        String token = bindEnding(FakePaymentProvider.CAPTURE_FAILS_SUFFIX);
        HoldOutcome created = provider.hold(hold(token, 60));
        assertThat(provider.confirmHold(created.holdId(), null).status()).isEqualTo(ProviderStatus.HELD);

        assertThatThrownBy(() -> provider.captureHold(created.holdId()))
                .isInstanceOf(HoldCancelledException.class)
                .extracting("code").isEqualTo(AtmosCodes.HOLD_CANCELLED);
        assertThat(provider.getHold(created.holdId()).status()).isEqualTo(ProviderStatus.RELEASED);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);
    }

    @Test
    void releasingIsIdempotentAndGivesTheMoneyBackOnce() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        provider.confirmHold(created.holdId(), null);

        assertThat(provider.releaseHold(created.holdId()).status()).isEqualTo(ProviderStatus.RELEASED);
        assertThat(provider.releaseHold(created.holdId()).status()).isEqualTo(ProviderStatus.RELEASED);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);

        assertThatThrownBy(() -> provider.captureHold(created.holdId())).isInstanceOf(HoldCancelledException.class);
    }

    @Test
    void aLapsedHoldCanStillBeReleased() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        provider.confirmHold(created.holdId(), null);
        clock.advance(Duration.ofMinutes(61));

        assertThat(provider.releaseHold(created.holdId()).status()).isEqualTo(ProviderStatus.RELEASED);
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);
    }

    @Test
    void aCapturedHoldCannotBeReleasedButIsReversedByItsId() {
        String token = bind(UZCARD);
        HoldOutcome created = provider.hold(hold(token, 60));
        provider.confirmHold(created.holdId(), null);
        provider.captureHold(created.holdId());

        assertThatThrownBy(() -> provider.releaseHold(created.holdId())).isInstanceOf(TransactionClosedException.class);

        provider.reverse(created.holdId(), "owner cancelled");
        assertThat(balanceOf(token)).isEqualTo(1_000_000_000L);
        assertThat(provider.getHold(created.holdId()).status()).isEqualTo(ProviderStatus.REVERSED);
        assertThatThrownBy(() -> provider.reverse(created.holdId(), "again")).isInstanceOf(TransactionClosedException.class);
    }

    @Test
    void holdsHaveTheMinimumToo() {
        String token = bind(UZCARD);
        HoldRequest tooSmall = new HoldRequest(List.of(Split.owner("rcp-1", 5_000_000L), Split.platform(40_000L)),
                token, UUID.randomUUID().toString(), 60);

        refusedWith(() -> provider.hold(tooSmall), AtmosCodes.WRONG_AMOUNT);
    }

    // ---- production ----

    @Test
    void itRefusesToStartUnderTheProdOrAtmosProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        MockEnvironment atmos = new MockEnvironment();
        atmos.setActiveProfiles("dev", "atmos");

        assertThatThrownBy(() -> newProvider(prod).refuseInProduction()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newProvider(atmos).refuseInProduction()).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> newProvider(new MockEnvironment()).refuseInProduction()).doesNotThrowAnyException();
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
