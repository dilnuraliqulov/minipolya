package uz.minipolya.minipolya_backend.provider.atmos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.AtmosCodes;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.HoldCancelledException;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.PendingCharge;
import uz.minipolya.minipolya_backend.provider.ProviderCheck;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.provider.TransactionClosedException;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Against a mocked ATMOS: no real call is ever made. Answers are shaped like the docs' examples. */
@ExtendWith(OutputCaptureExtension.class)
class AtmosPaymentProviderTest {

    private static final String BASE = "https://apigw.test";
    /** The docs' own token call has grant_type in the query as well as in the form. */
    private static final String TOKEN_URL = BASE + "/token?grant_type=client_credentials";
    private static final String TOKEN_ANSWER = """
            {"access_token":"tok-abc","scope":"am_application_scope default","token_type":"Bearer","expires_in":3600}
            """;
    private static final String OK_INNER = "\"code\":\"OK\",\"description\":\"Нет ошибок\"";
    private static final String OK = "\"result\":{" + OK_INNER + "}";

    private final MovableClock clock = new MovableClock();
    private MockRestServiceServer server;
    /** For a provider a test builds itself (other profiles, other settings). */
    private MockRestServiceServer server2;
    private AtmosPaymentProvider provider;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        AppProperties.Atmos atmos = properties.getAtmos();
        atmos.setBaseUrl(BASE);
        atmos.setConsumerKey("key");
        atmos.setConsumerSecret("secret");
        atmos.setStoreId("777");
        atmos.setPlatformRecipientId("900");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = providerWith(properties, builder);
    }

    /** The provider as a developer's machine has it: no profile, so no staging fallback. */
    private AtmosPaymentProvider providerWith(AppProperties properties, RestClient.Builder builder) {
        return onProfiles(properties, builder);
    }

    private AtmosPaymentProvider onProfiles(AppProperties properties, RestClient.Builder builder,
                                            String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new AtmosPaymentProvider(properties, builder, clock, environment);
    }

    private RestClient.Builder server2() {
        RestClient.Builder builder = RestClient.builder();
        server2 = MockRestServiceServer.bindTo(builder).build();
        return builder;
    }

    private void expectTokenOn(MockRestServiceServer target) {
        target.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic a2V5OnNlY3JldA=="))
                .andRespond(withSuccess(TOKEN_ANSWER, MediaType.APPLICATION_JSON));
    }

    private void expectToken() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic a2V5OnNlY3JldA=="))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(TOKEN_ANSWER, MediaType.APPLICATION_JSON));
    }

    private ChargeRequest charge() {
        return ChargeRequest.withToken(List.of(Split.owner("501", 20_304_569L), Split.platform(5_431L)),
                "card-token", "0190c3a1-0000-7000-8000-000000000001");
    }

    /** An ordinary booking: the owner is the only recipient, because the platform takes nothing. */
    private ChargeRequest oneRecipient() {
        return ChargeRequest.withToken(List.of(Split.owner("501", 20_304_569L)),
                "card-token", "0190c3a1-0000-7000-8000-000000000001");
    }

    // ---- token ----

    @Test
    void theTokenIsCachedUntilFiveMinutesBeforeItExpires() {
        expectToken();
        server.expect(requestTo(BASE + "/partner/remove-card"))
                .andExpect(header("Authorization", "Bearer tok-abc"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/partner/remove-card"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));
        provider.removeCard("1666711", "t1");
        clock.advance(Duration.ofMinutes(50));            // 3600 s − 5 min margin = 55 min
        provider.removeCard("1666711", "t1");
        server.verify();

        server.reset();
        expectToken();                                    // 56 min in: a fresh token
        server.expect(requestTo(BASE + "/partner/remove-card"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));
        clock.advance(Duration.ofMinutes(6));
        provider.removeCard("1666711", "t1");
        server.verify();
    }

    @Test
    void a401RefreshesTheTokenOnceAndRetries() {
        expectToken();
        server.expect(requestTo(BASE + "/partner/remove-card")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken();
        server.expect(requestTo(BASE + "/partner/remove-card"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        provider.removeCard("1666711", "t1");

        server.verify();
    }

    // ---- charges ----

    @Test
    void aSplitChargeIsCreatedPreAppliedAndApplied() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/bulk/pay/create"))
                .andExpect(jsonPath("$.store_id").value(777))
                .andExpect(jsonPath("$.params.length()").value(2))
                .andExpect(jsonPath("$.params[0].store_id").value(501))
                .andExpect(jsonPath("$.params[0].amount").value(20_304_569))
                .andExpect(jsonPath("$.params[0].account").value("0190c3a1-0000-7000-8000-000000000001"))
                .andExpect(jsonPath("$.params[1].store_id").value(900))   // the platform's recipient
                .andExpect(jsonPath("$.params[1].amount").value(5_431))
                .andExpect(jsonPath("$.params[0].ofd_items").doesNotExist())
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":[1001,1002],\"store_transactions\":[]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/merchant/bulk/pay/pre-apply"))
                .andExpect(jsonPath("$.card_token").value("card-token"))
                .andExpect(jsonPath("$.transaction_id[0]").value(1001))
                .andExpect(jsonPath("$.card_number").doesNotExist())
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/merchant/bulk/pay/apply"))
                .andExpect(jsonPath("$.otp").value("111111"))
                .andExpect(jsonPath("$.transaction_id[1]").value(1002))
                .andRespond(withSuccess("{" + OK + ",\"store_transaction\":["
                        + "{\"success_trans_id\":5001,\"trans_id\":1001,\"confirmed\":true,\"commission_value\":\"304568\",\"status_code\":\"0\"},"
                        + "{\"success_trans_id\":5002,\"trans_id\":1002,\"confirmed\":true,\"commission_value\":\"81\",\"status_code\":\"0\"}]}",
                        MediaType.APPLICATION_JSON));

        PendingCharge pending = provider.charge(charge());
        ChargeOutcome outcome = provider.confirmCharge(pending.transactionIds(), null);

        assertThat(pending.transactionIds()).containsExactly("1001", "1002");
        assertThat(pending.otpRequired()).isFalse();
        assertThat(outcome.status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(outcome.successTransIds()).containsExactly("5001", "5002");
        assertThat(outcome.commission()).isEqualTo(304_649L);
        server.verify();
    }

    @Test
    void receiptLinesAreSentOnlyWhenBothOfdCodesAreSet() {
        properties.getAtmos().setOfdCodeRental("10305001001000000");
        properties.getAtmos().setOfdCodeService("10399001001000000");
        expectToken();
        server.expect(requestTo(BASE + "/merchant/bulk/pay/create"))
                .andExpect(jsonPath("$.params[0].ofd_items[0].ofd_code").value("10305001001000000"))
                .andExpect(jsonPath("$.params[1].ofd_items[0].ofd_code").value("10399001001000000"))
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":[1001,1002]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/merchant/bulk/pay/pre-apply"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        provider.charge(charge());

        server.verify();
    }

    @Test
    void aDeclinedCardIsAFailedOutcomeCarryingTheCode() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/bulk/pay/apply"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-057\",\"description\":\"Недостаточно средств\"}}",
                        MediaType.APPLICATION_JSON));

        ChargeOutcome outcome = provider.confirmCharge(List.of("1001", "1002"), null);

        assertThat(outcome.status()).isEqualTo(ProviderStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("STPIMS-ERR-057");
    }

    /** The same decline on the single-payment endpoint, which one transaction uses. */
    @Test
    void aDeclinedSinglePaymentIsAFailedOutcomeToo() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/apply"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-057\",\"description\":\"Недостаточно средств\"}}",
                        MediaType.APPLICATION_JSON));

        ChargeOutcome outcome = provider.confirmCharge(List.of("1001"), null);

        assertThat(outcome.status()).isEqualTo(ProviderStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("STPIMS-ERR-057");
    }

    @Test
    void aLostAnswerIsATimeoutAndGetChargeTellsWhatHappened() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/apply")).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });
        server.expect(requestTo(BASE + "/merchant/pay/get"))
                .andExpect(jsonPath("$.transaction_id").value(1001))
                .andExpect(jsonPath("$.store_id").value(777))
                .andRespond(withSuccess("{" + OK + ",\"store_transaction\":{\"success_trans_id\":5001,\"trans_id\":1001,"
                        + "\"confirmed\":true,\"commission_value\":\"300\",\"status_code\":\"0\",\"status_message\":\"Success\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.confirmCharge(List.of("1001"), null))
                .isInstanceOf(ProviderTimeoutException.class);
        ChargeOutcome later = provider.getCharge("1001");

        assertThat(later.status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(later.successTransIds()).containsExactly("5001");
        server.verify();
    }

    @Test
    void reverseGivesTheWholeTransactionBack() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/reverse"))
                .andExpect(jsonPath("$.transaction_id").value(5001))
                .andExpect(jsonPath("$.reason").value("cancelled"))
                .andExpect(jsonPath("$.hold_amount").doesNotExist())   // never a partial refund
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":5001}", MediaType.APPLICATION_JSON));

        provider.reverse("5001", "cancelled");

        server.verify();
    }

    @Test
    void reversingAClosedTransactionIsTransactionClosed() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/reverse"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-092\",\"description\":\"Транзакция закрыта\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.reverse("5001", "again")).isInstanceOf(TransactionClosedException.class);
    }

    @Test
    void reversingAnAlreadyReversedTransactionIsTransactionClosedToo() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/reverse"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-100\",\"description\":\"ранее была отменена\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.reverse("5001", "again")).isInstanceOf(TransactionClosedException.class);
    }

    @Test
    void aBindingCodeCannotBeResentAtAtmos() {
        assertThatThrownBy(() -> provider.resendBindingCode("442"))
                .isInstanceOf(uz.minipolya.minipolya_backend.provider.PaymentProviderException.class)
                .extracting("code").isEqualTo(uz.minipolya.minipolya_backend.provider.AtmosCodes.RESEND_UNSUPPORTED);
    }

    @Test
    void aConfirmedBindingGivesTheTokenMaskedNumberExpiryAndHolder() {
        expectToken();
        server.expect(requestTo(BASE + "/partner/bind-card/confirm"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"OK\"},\"data\":{\"card_id\":1579076,"
                                + "\"pan\":\"986009******1840\",\"expiry\":\"2505\",\"card_holder\":\"TEST\","
                                + "\"balance\":1000000000,\"phone\":\"********9999\",\"card_token\":\"tok-x\"},"
                                + "\"transaction_id\":4789}",
                        MediaType.APPLICATION_JSON));

        var card = provider.bindCardConfirm("383", "123456");

        assertThat(card.cardToken()).isEqualTo("tok-x");
        assertThat(card.cardId()).isEqualTo("1579076");
        assertThat(card.maskedPan()).isEqualTo("9860 •••• •••• 1840");
        assertThat(card.expiry()).isEqualTo("05/25");
        assertThat(card.cardHolder()).isEqualTo("TEST");
    }

    @Test
    void anUnknownCodeIsAProviderExceptionCarryingIt() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/reverse"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-999\",\"description\":\"?\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.reverse("5001", "x"))
                .isInstanceOf(PaymentProviderException.class)
                .extracting("code").isEqualTo("STPIMS-ERR-999");
    }

    // ---- holds ----

    /**
     * Field for field the docs' {@code POST /hold/multiple/create} example: {@code card_token}
     * and {@code duration} at the top, one item per split with {@code store_id},
     * {@code account}, {@code payment_details} and {@code amount} — every one of them quoted,
     * because every hold example in the docs quotes them.
     */
    @Test
    void aHoldIsCreatedWithItsItemsAndAppliedWithTheTokenOtp() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/multiple/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.card_token").value("card-token"))
                .andExpect(jsonPath("$.duration").value("600"))
                .andExpect(jsonPath("$.store_id").doesNotExist())      // not a field of this call
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].store_id").value("501"))
                .andExpect(jsonPath("$.items[0].account").value("a"))
                .andExpect(jsonPath("$.items[0].payment_details").value("Pitch rental"))
                .andExpect(jsonPath("$.items[0].amount").value("5076143"))
                .andExpect(jsonPath("$.items[1].store_id").value("900"))
                .andExpect(jsonPath("$.items[1].payment_details").value("MiniPolya service"))
                .andExpect(jsonPath("$.items[1].amount").value("2533857"))
                .andRespond(withSuccess("{" + OK + ",\"parent_id\":3001,\"items\":[{\"result\":null,"
                        + "\"hold_id\":3002,\"account\":\"a\"},{\"result\":null,\"hold_id\":3003}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/hold/multiple/apply/3001"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.otp").value("111111"))
                .andExpect(jsonPath("$.hold_id").doesNotExist())       // the id travels in the path
                .andRespond(withSuccess("{" + OK + ",\"parent_id\":3001,\"items\":[{\"hold_id\":3002}]}",
                        MediaType.APPLICATION_JSON));

        HoldOutcome created = provider.hold(new HoldRequest(
                List.of(Split.owner("501", 5_076_143L), Split.platform(2_533_857L)), "card-token", "a", 600));
        HoldOutcome held = provider.confirmHold(created.holdId(), null);

        assertThat(created.holdId()).isEqualTo("3001");
        assertThat(created.holdTill()).isEqualTo(clock.now().plusMinutes(600));
        assertThat(held.status()).isEqualTo(ProviderStatus.HELD);
        server.verify();
    }

    /** A store id is text: {@code "0001"} must not reach ATMOS as the number 1. */
    @Test
    void aStoreIdThatStartsWithAZeroKeepsIt() {
        properties.getAtmos().setPlatformRecipientId("0002");
        expectToken();
        server.expect(requestTo(BASE + "/hold/multiple/create"))
                .andExpect(jsonPath("$.items[0].store_id").value("0001"))
                .andExpect(jsonPath("$.items[1].store_id").value("0002"))
                .andRespond(withSuccess("{" + OK + ",\"parent_id\":3001}", MediaType.APPLICATION_JSON));

        provider.hold(new HoldRequest(List.of(Split.owner("0001", 5_076_143L), Split.platform(2_533_857L)),
                "card-token", "a", 600));

        server.verify();
    }

    /**
     * The items get a {@code hold_till} each, milliseconds apart in the docs' own example.
     * The hold is only as good as the first one to let go.
     */
    @Test
    void theHoldLastsUntilTheEarliestItemLetsGo() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/multiple/apply/3001"))
                .andRespond(withSuccess("{" + OK + ",\"parent_id\":3001,\"items\":["
                        + "{\"hold_id\":3002,\"hold_till\":\"2026-09-28T16:46:23.118Z\"},"
                        + "{\"hold_id\":3003,\"hold_till\":\"2026-09-28T16:46:22.762Z\"}]}",
                        MediaType.APPLICATION_JSON));

        HoldOutcome held = provider.confirmHold("3001", null);

        assertThat(held.holdTill()).isEqualTo(OffsetDateTime.parse("2026-09-28T16:46:22.762Z"));
    }

    /**
     * An item may carry its own refusal under an OK envelope. Half a team's money held is
     * not the hold that was asked for, so the item's code decides the whole hold.
     */
    @Test
    void anItemThatDeclinesFailsTheWholeHold() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/multiple/apply/3001"))
                .andRespond(withSuccess("{" + OK + ",\"parent_id\":3001,\"items\":[{\"result\":{" + OK_INNER
                        + "},\"hold_id\":3002},{\"result\":{\"code\":\"STPIMS-ERR-057\","
                        + "\"description\":\"Недостаточно средств\"},\"hold_id\":3003}]}",
                        MediaType.APPLICATION_JSON));

        HoldOutcome held = provider.confirmHold("3001", null);

        assertThat(held.status()).isEqualTo(ProviderStatus.FAILED);
        assertThat(held.errorCode()).isEqualTo("STPIMS-ERR-057");
    }

    @Test
    void anItemRefusedForAnyOtherReasonIsARefusal() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/multiple/apply/3001"))
                .andRespond(withSuccess("{" + OK + ",\"items\":[{\"result\":{\"code\":\"STPIMS-ERR-037\","
                        + "\"description\":\"Store ID не найден\"},\"hold_id\":3002}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.confirmHold("3001", null))
                .isInstanceOf(PaymentProviderException.class)
                .extracting(e -> ((PaymentProviderException) e).getCode()).isEqualTo("STPIMS-ERR-037");
    }

    @Test
    void aCaptureOnACancelledHoldIsHoldCancelled() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").doesNotExist())        // always the full amount
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-145\",\"description\":\"холдирование отменено\"},"
                        + "\"status\":-242,\"processing\":\"UZCARD\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.captureHold("3001")).isInstanceOf(HoldCancelledException.class);
    }

    @Test
    void releasingSendsTheCancelSms() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(jsonPath("$.send_cancel_sms").value(true))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        assertThat(provider.releaseHold("3001").status()).isEqualTo(ProviderStatus.RELEASED);
    }

    /**
     * The docs' own sample answer to {@code DELETE /hold/{id}} is {@code STPIMS-ERR-145}
     * ("холдирование отменено") — the confirmation, not a failure. Reading it as a
     * failure threw {@link HoldCancelledException} out of every release that worked.
     */
    @Test
    void aReleaseIsADoneReleaseEvenWhenItAnswersHoldCancelled() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-145\",\"description\":"
                        + "\"холдирование отменено\"},\"status\":-242,\"processing\":\"UZCARD\"}",
                        MediaType.APPLICATION_JSON));

        HoldOutcome released = provider.releaseHold("3001");

        assertThat(released.status()).isEqualTo(ProviderStatus.RELEASED);
        assertThat(released.errorCode()).isEqualTo("STPIMS-ERR-145");
    }

    /** A hold that had already lapsed is also a hold that is gone. */
    @Test
    void aReleaseOfAHoldThatHadAlreadyExpiredIsDoneToo() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-144\",\"description\":"
                        + "\"Время холдирования истекло\"}}", MediaType.APPLICATION_JSON));

        assertThat(provider.releaseHold("3001").status()).isEqualTo(ProviderStatus.RELEASED);
    }

    /** {@code -143} is the one code that means the money is still held. */
    @Test
    void aReleaseThatDidNotHappenIsARefusal() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-143\",\"description\":"
                        + "\"холдирование не отменено\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.releaseHold("3001"))
                .isInstanceOf(PaymentProviderException.class)
                .extracting(e -> ((PaymentProviderException) e).getCode()).isEqualTo("STPIMS-ERR-143");
    }

    @Test
    void aHoldReadBackIsHeldWhenItIsConfirmed() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{" + OK + ",\"store_transaction\":{\"confirmed\":true,"
                        + "\"amount\":5000000}}", MediaType.APPLICATION_JSON));

        assertThat(provider.getHold("3001").status()).isEqualTo(ProviderStatus.HELD);
    }

    /**
     * F6: a store_transaction carrying a success_trans_id is money taken, not reserved — a
     * capture whose answer was lost reads back as captured, never as released.
     */
    @Test
    void aHoldReadBackWithASuccessIdIsCaptured() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{" + OK + ",\"store_transaction\":{\"confirmed\":true,"
                        + "\"success_trans_id\":987654,\"commission_value\":\"75000\",\"amount\":5000000}}",
                        MediaType.APPLICATION_JSON));

        HoldOutcome read = provider.getHold("3001");

        assertThat(read.status()).isEqualTo(ProviderStatus.CAPTURED);
        assertThat(read.commission()).isEqualTo(75_000L);
    }

    /** A multiple hold answers one transaction per item: captured only when every item was. */
    @Test
    void aMultipleHoldIsCapturedOnlyWhenEveryItemWasDebited() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andRespond(withSuccess("{" + OK + ",\"store_transaction\":["
                        + "{\"confirmed\":true,\"success_trans_id\":11},"
                        + "{\"confirmed\":true,\"success_trans_id\":null}]}", MediaType.APPLICATION_JSON));

        assertThat(provider.getHold("3001").status()).isEqualTo(ProviderStatus.HELD);
    }

    /** F10: a blocked card and a wrong code at apply are answers, not unknowns left processing. */
    @Test
    void aBlockedCardOrAWrongCodeAtApplyIsAFailedOutcome() {
        for (String code : new String[]{"STPIMS-ERR-088", "STPIMS-ERR-098"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer one = MockRestServiceServer.bindTo(builder).build();
            AtmosPaymentProvider fresh = providerWith(properties, builder);
            expectTokenOn(one);
            one.expect(requestTo(BASE + "/merchant/pay/apply"))
                    .andRespond(withSuccess("{\"result\":{\"code\":\"" + code + "\"}}", MediaType.APPLICATION_JSON));

            ChargeOutcome outcome = fresh.confirmCharge(List.of("1001"), null);

            assertThat(outcome.status()).isEqualTo(ProviderStatus.FAILED);
            assertThat(outcome.errorCode()).isEqualTo(code);
        }
        assertThat(uz.minipolya.minipolya_backend.provider.DeclineReason.of("STPIMS-ERR-098"))
                .isEqualTo(uz.minipolya.minipolya_backend.provider.DeclineReason.WRONG_OTP);
        assertThat(uz.minipolya.minipolya_backend.provider.DeclineReason.of("STPIMS-ERR-088"))
                .isEqualTo(uz.minipolya.minipolya_backend.provider.DeclineReason.CARD_DECLINED);
    }

    /** {@code -142} is a hold created and never applied: pending, not an error. */
    @Test
    void aHoldThatWasNeverAppliedReadsAsPending() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/3001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-142\",\"description\":"
                        + "\"холдирование не подтверждено\"}}", MediaType.APPLICATION_JSON));

        HoldOutcome read = provider.getHold("3001");

        assertThat(read.status()).isEqualTo(ProviderStatus.PENDING);
        assertThat(read.errorCode()).isEqualTo("STPIMS-ERR-142");
    }

    // ---- what a refusal says in the log ----

    /**
     * The 403 this audit started from. The log must name the method, the path, the status and
     * the body — the body is the only place a gateway says whether it refused the path, the
     * request or the merchant account — and it must still not name a card or a token.
     */
    @Test
    void aForbiddenCallIsLoggedWithItsMethodPathStatusAndBody(CapturedOutput output) {
        expectToken();
        server.expect(requestTo(BASE + "/hold/multiple/create"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"fault\":{\"code\":900908,\"message\":\"Resource forbidden\","
                                + "\"card_token\":\"tok-should-not-be-logged\"}}"));

        assertThatThrownBy(() -> provider.hold(new HoldRequest(
                List.of(Split.owner("501", 5_076_143L), Split.platform(2_533_857L)), "card-token", "a", 600)))
                .isInstanceOf(PaymentProviderException.class)
                .extracting(e -> ((PaymentProviderException) e).getCode()).isEqualTo("HTTP_403");

        assertThat(output.getAll())
                .contains("hold/multiple/create")
                .contains("POST")
                .contains("HTTP 403")
                .contains("Resource forbidden")
                .contains("900908")
                .doesNotContain("tok-should-not-be-logged");
    }

    /** A result code that is not OK is logged the same way, body and all. */
    @Test
    void aRefusedResultCodeIsLoggedWithItsBody(CapturedOutput output) {
        expectToken();
        server.expect(requestTo(BASE + "/hold/multiple/create"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-037\",\"description\":"
                        + "\"Store ID not found\"},\"card_pan\":\"986009******1840\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.hold(new HoldRequest(
                List.of(Split.owner("501", 5_076_143L), Split.platform(2_533_857L)), "card-token", "a", 600)))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(output.getAll())
                .contains("/hold/multiple/create")
                .contains("code=STPIMS-ERR-037")
                .contains("Store ID not found")
                .doesNotContain("986009******1840");
    }

    // ---- one recipient: the single-payment flow ----

    /**
     * Shaped like the docs' pay/create → pay/pre-apply → pay/apply examples: amount a number
     * in tiyin, ids numbers, the apply answering one store_transaction object with the
     * receipt URL beside it rather than inside it.
     */
    @Test
    void oneRecipientIsPaidThroughTheSinglePaymentEndpoints() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/create"))
                .andExpect(method(HttpMethod.POST))
                // The store being paid, the payment id as the account, tiyin as a number.
                .andExpect(jsonPath("$.store_id").value(501))
                .andExpect(jsonPath("$.account").value("0190c3a1-0000-7000-8000-000000000001"))
                .andExpect(jsonPath("$.amount").value(20_304_569L))
                .andExpect(jsonPath("$.params").doesNotExist())
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":111111,\"store_transaction\":"
                        + "{\"success_trans_id\":null,\"trans_id\":111111,\"confirmed\":false,"
                        + "\"commission_value\":\"0\",\"status_code\":null}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/merchant/pay/pre-apply"))
                // Our own store here, and the transaction just created.
                .andExpect(jsonPath("$.store_id").value(777))
                .andExpect(jsonPath("$.transaction_id").value(111111))
                .andExpect(jsonPath("$.card_token").value("card-token"))
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":111111}", MediaType.APPLICATION_JSON));

        PendingCharge pending = provider.charge(oneRecipient());

        assertThat(pending.transactionIds()).containsExactly("111111");
        assertThat(pending.otpRequired()).isFalse();
        server.verify();
    }

    @Test
    void aSingleChargeIsConfirmedWithPayApplyAndReadsItsOneTransaction() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/apply"))
                .andExpect(jsonPath("$.store_id").value(777))
                .andExpect(jsonPath("$.transaction_id").value(111111))
                // A token payment confirms with the fixed code; no SMS was sent.
                .andExpect(jsonPath("$.otp").value("111111"))
                .andRespond(withSuccess("{" + OK + ",\"store_transaction\":{\"success_trans_id\":2730590,"
                        + "\"trans_id\":111111,\"account\":\"0001\",\"amount\":20304569,\"confirmed\":true,"
                        + "\"commission_value\":\"300\",\"status_code\":\"0\",\"status_message\":\"Success\"},"
                        + "\"ofd_url\":\"https://ofd.test/receipt/1\"}", MediaType.APPLICATION_JSON));

        ChargeOutcome outcome = provider.confirmCharge(List.of("111111"), null);

        assertThat(outcome.status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(outcome.successTransIds()).containsExactly("2730590");
        assertThat(outcome.commission()).isEqualTo(300L);
        // Top-level in a single apply, inside each transaction in a bulk one.
        assertThat(outcome.ofdUrl()).isEqualTo("https://ofd.test/receipt/1");
        server.verify();
    }

    /** A card number rather than a token: ATMOS sends the cardholder an SMS code. */
    @Test
    void aSinglePaymentWithACardNumberAsksForTheSmsCode() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/create"))
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":111111}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/merchant/pay/pre-apply"))
                .andExpect(jsonPath("$.card_number").value("8600490744313347"))
                // YYmm, as the docs print it.
                .andExpect(jsonPath("$.expiry").value("2801"))
                .andExpect(jsonPath("$.card_token").doesNotExist())
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        PendingCharge pending = provider.charge(new ChargeRequest(List.of(Split.owner("501", 20_304_569L)),
                null, "8600 4907 4431 3347", "01/28", "0190c3a1-0000-7000-8000-000000000001"));

        assertThat(pending.otpRequired()).isTrue();
        server.verify();
    }

    /** Two recipients are a multi-transaction, which is a different set of endpoints. */
    @Test
    void twoRecipientsStillGoThroughTheBulkEndpoints() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/bulk/pay/create"))
                .andExpect(jsonPath("$.params.length()").value(2))
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":[1001,1002]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/merchant/bulk/pay/pre-apply"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        assertThat(provider.charge(charge()).transactionIds()).containsExactly("1001", "1002");
        server.verify();
    }

    // ---- pay/get ----

    /**
     * The docs' own pay/get example: result STPIMS-ERR-092 ("Транзакция закрыта")
     * together with a confirmed transaction and its success_trans_id. The transaction is the
     * answer — reading the code instead would report a payment that went through as given
     * back, and the reconcile job would fail a booking the player had paid for.
     */
    @Test
    void aClosedButConfirmedTransactionIsReadAsPaid() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/get"))
                .andExpect(jsonPath("$.store_id").value(777))
                .andExpect(jsonPath("$.transaction_id").value(51827))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-092\","
                        + "\"description\":\"Транзакция закрыта\"},"
                        + "\"store_transaction\":{\"success_trans_id\":2730590,\"trans_id\":51827,"
                        + "\"amount\":5000000,\"confirmed\":true,\"commission_value\":\"0\","
                        + "\"status_code\":\"0\",\"status_message\":\"Success\"}}", MediaType.APPLICATION_JSON));

        ChargeOutcome outcome = provider.getCharge("51827");

        assertThat(outcome.status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(outcome.successTransIds()).containsExactly("2730590");
        server.verify();
    }

    /** Closed with nothing to read is all we know: the money is not ours. */
    @Test
    void aClosedTransactionWithNothingToReadIsAGiveBack() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/get"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"STPIMS-ERR-092\","
                        + "\"description\":\"Транзакция закрыта\"}}",
                        MediaType.APPLICATION_JSON));

        ChargeOutcome outcome = provider.getCharge("51827");

        assertThat(outcome.status()).isEqualTo(ProviderStatus.REVERSED);
        assertThat(outcome.errorCode()).isEqualTo("STPIMS-ERR-092");
    }

    /** An unconfirmed transaction is not an answer yet: ask again rather than guess. */
    @Test
    void anUnconfirmedTransactionStaysPending() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/get"))
                .andRespond(withSuccess("{" + OK + ",\"store_transaction\":{\"success_trans_id\":null,"
                        + "\"trans_id\":51827,\"confirmed\":false}}", MediaType.APPLICATION_JSON));

        assertThat(provider.getCharge("51827").status()).isEqualTo(ProviderStatus.PENDING);
    }

    // ---- what we do not know, we never call a failure ----

    /**
     * A 401 refreshes the token and tries once more; if THAT call times out it is still a
     * timeout. It used to escape as a transport exception, because an exception thrown
     * inside a catch block is not caught by its own try — and a payment read as failed when
     * it may have gone through is the one mistake that must never be made.
     */
    @Test
    void aTimeoutWhileRetryingAfterA401IsStillATimeout() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/get")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/get")).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });

        assertThatThrownBy(() -> provider.getCharge("51827"))
                .isInstanceOf(ProviderTimeoutException.class)
                .hasMessageContaining("pay/get");
        server.verify();
    }

    /** A gateway error page in place of JSON says nothing about whether the money moved. */
    @Test
    void anAnswerThatIsNotJsonIsAsUnknownAsNoAnswer() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/pay/apply"))
                .andRespond(withSuccess("<html><body>502 Bad Gateway</body></html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> provider.confirmCharge(List.of("111111"), null))
                .isInstanceOf(ProviderTimeoutException.class)
                .hasMessageContaining("could not read");
    }

    // ---- who the money goes to ----

    /**
     * With {@code terminal_id} the recipient is the terminal and {@code store_id} stays our
     * own merchant store — the shape the docs' single-payment example reads as. Which of the
     * two ATMOS pays out on is the open question the setting exists for.
     */
    @Test
    void theRecipientFieldDecidesWhoIsNamedWhere() {
        properties.getAtmos().setRecipientField("terminal_id");
        AtmosPaymentProvider terminals = providerWith(properties, server2());
        expectTokenOn(server2);
        server2.expect(requestTo(BASE + "/merchant/pay/create"))
                .andExpect(jsonPath("$.store_id").value(777))
                .andExpect(jsonPath("$.terminal_id").value("501"))
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":111111}", MediaType.APPLICATION_JSON));
        server2.expect(requestTo(BASE + "/merchant/pay/pre-apply"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        terminals.charge(oneRecipient());

        server2.verify();
    }

    @Test
    void everyBulkItemIsNamedTheSameWay() {
        properties.getAtmos().setRecipientField(" Terminal-ID ");
        AtmosPaymentProvider terminals = providerWith(properties, server2());
        expectTokenOn(server2);
        server2.expect(requestTo(BASE + "/merchant/bulk/pay/create"))
                .andExpect(jsonPath("$.store_id").value(777))
                .andExpect(jsonPath("$.params[0].store_id").value(777))
                .andExpect(jsonPath("$.params[0].terminal_id").value("501"))
                .andExpect(jsonPath("$.params[1].terminal_id").value("900"))
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":[1,2]}", MediaType.APPLICATION_JSON));
        server2.expect(requestTo(BASE + "/merchant/bulk/pay/pre-apply"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        terminals.charge(charge());

        server2.verify();
    }

    @Test
    void theRecipientFieldIsOneOfTwoAndIsNotEchoedBack() {
        assertThat(AtmosRecipientField.of(null)).isEqualTo(AtmosRecipientField.STORE_ID);
        assertThat(AtmosRecipientField.of("")).isEqualTo(AtmosRecipientField.STORE_ID);
        assertThat(AtmosRecipientField.of(" Terminal-ID ")).isEqualTo(AtmosRecipientField.TERMINAL_ID);
        assertThat(AtmosRecipientField.TERMINAL_ID.field()).isEqualTo("terminal_id");
        assertThatThrownBy(() -> AtmosRecipientField.of("kQ8sW2mZ"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ATMOS_RECIPIENT_FIELD")
                .hasMessageNotContaining("kQ8sW2mZ");
    }

    // ---- a recipient we do not know yet ----

    private ChargeRequest noRecipients() {
        // An owner whose admin has not entered an ATMOS recipient, plus the platform's own
        // split while ATMOS_PLATFORM_RECIPIENT_ID is still empty.
        return ChargeRequest.withToken(List.of(Split.owner(null, 20_304_569L), Split.platform(5_431L)),
                "card-token", "0190c3a1-0000-7000-8000-000000000001");
    }

    /**
     * On a staging test server the point is to walk a real ATMOS payment end to end before
     * any owner has a recipient id, so the splits collapse into one payment to our own store
     * — for the same total, and nobody is paid out.
     */
    @Test
    void underStagingAPaymentWithNoRecipientGoesToOurOwnStore() {
        properties.getAtmos().setPlatformRecipientId(null);
        AtmosPaymentProvider staging = onProfiles(properties, server2(), "staging", "atmos");
        expectTokenOn(server2);
        server2.expect(requestTo(BASE + "/merchant/pay/create"))
                // One payment, to us, for the whole amount: 20 304 569 + 5 431.
                .andExpect(jsonPath("$.store_id").value(777))
                .andExpect(jsonPath("$.amount").value(20_310_000L))
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":111111}", MediaType.APPLICATION_JSON));
        server2.expect(requestTo(BASE + "/merchant/pay/pre-apply"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        assertThat(staging.charge(noRecipients()).transactionIds()).containsExactly("111111");

        server2.verify();
    }

    /** A hold is the same money by another name, so it falls back the same way. */
    @Test
    void underStagingAHoldWithNoRecipientDoesTheSame() {
        properties.getAtmos().setPlatformRecipientId(null);
        AtmosPaymentProvider staging = onProfiles(properties, server2(), "staging", "atmos");
        expectTokenOn(server2);
        server2.expect(requestTo(BASE + "/hold/multiple/create"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].store_id").value("777"))
                .andExpect(jsonPath("$.items[0].amount").value("7609999"))
                .andRespond(withSuccess("{" + OK + ",\"parent_id\":3001}", MediaType.APPLICATION_JSON));

        staging.hold(new HoldRequest(List.of(Split.owner(null, 5_076_142L), Split.platform(2_533_857L)),
                "card-token", "a", 600));

        server2.verify();
    }

    /** Never where real money moves: prod refuses, whatever else is active beside it. */
    @Test
    void underProdOrAnythingButStagingTheyAreRefused() {
        properties.getAtmos().setPlatformRecipientId(null);
        for (String[] profiles : new String[][] {{"prod", "atmos"}, {"prod", "staging", "atmos"}, {"atmos"}}) {
            AtmosPaymentProvider real = onProfiles(properties, RestClient.builder(), profiles);
            assertThatThrownBy(() -> real.charge(noRecipients()))
                    .isInstanceOf(PaymentProviderException.class)
                    .hasMessageContaining("recipient");
        }
    }

    /** With every recipient known, staging pays them and not us. */
    @Test
    void stagingPaysTheRealRecipientsWhenItKnowsThem() {
        AtmosPaymentProvider staging = onProfiles(properties, server2(), "staging", "atmos");
        expectTokenOn(server2);
        server2.expect(requestTo(BASE + "/merchant/bulk/pay/create"))
                .andExpect(jsonPath("$.params.length()").value(2))
                .andExpect(jsonPath("$.params[0].store_id").value(501))
                .andExpect(jsonPath("$.params[1].store_id").value(900))
                .andRespond(withSuccess("{" + OK + ",\"transaction_id\":[1,2]}", MediaType.APPLICATION_JSON));
        server2.expect(requestTo(BASE + "/merchant/bulk/pay/pre-apply"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        staging.charge(charge());

        server2.verify();
    }

    // ---- the provider check ----

    @Test
    void theCheckAsksForATokenAndReportsTheHost() {
        expectToken();

        ProviderCheck check = provider.check();

        assertThat(check.ok()).isTrue();
        assertThat(check.provider()).isEqualTo("atmos");
        assertThat(check.host()).isEqualTo("apigw.test");
        // Nothing is configured, and an unset ATMOS_ENVIRONMENT means the sandbox.
        assertThat(check.environment()).isEqualTo("sandbox");
        assertThat(check.errorCode()).isNull();
        server.verify();
    }

    /** A cached token proves only that one was bought earlier, so the check buys a new one. */
    @Test
    void theCheckNeverAnswersFromTheCachedToken() {
        expectToken();
        server.expect(requestTo(BASE + "/partner/remove-card"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));
        // A second /token, although the one bought above is still valid for an hour.
        expectToken();

        provider.removeCard("1666711", "t1");

        assertThat(provider.check().ok()).isTrue();
        server.verify();
    }

    /**
     * The sandbox answers on the same host as production, so the host says nothing about
     * which one this is: the check reports what ATMOS_ENVIRONMENT says, failure included.
     */
    @Test
    void theCheckReportsWhichSideOfAtmosTheCredentialsAreFor() {
        properties.getAtmos().setEnvironment(" Production ");
        expectToken();

        assertThat(provider.check().environment()).isEqualTo("production");

        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        ProviderCheck failed = provider.check();
        assertThat(failed.ok()).isFalse();
        assertThat(failed.environment()).isEqualTo("production");
    }

    @Test
    void aRefusedTokenIsAFailedCheckCarryingTheCode() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ProviderCheck check = provider.check();

        assertThat(check.ok()).isFalse();
        assertThat(check.provider()).isEqualTo("atmos");
        assertThat(check.host()).isEqualTo("apigw.test");
        assertThat(check.errorCode()).isEqualTo("HTTP_401");
    }

    @Test
    void anAnswerWithNoTokenAndNoAnswerAtAllEachHaveTheirOwnCode() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(provider.check().errorCode()).isEqualTo("TOKEN");

        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });
        assertThat(provider.check().errorCode()).isEqualTo("TIMEOUT");
    }

    /** The check is shown to an admin, so it may carry no token and no credential at all. */
    @Test
    void theCheckCarriesNeitherTheTokenNorTheCredentials() {
        expectToken();

        ProviderCheck check = provider.check();

        assertThat(check.toString()).doesNotContain("tok-abc", "key", "secret")
                .contains("atmos", "apigw.test", "sandbox");
        assertThat(check.host()).isEqualTo("apigw.test").isNotEqualTo(BASE);
    }

    @Test
    void onlyTheHostOfABaseUrlIsEverReported() {
        assertThat(ProviderCheck.hostOf("https://apigw.atmos.uz")).isEqualTo("apigw.atmos.uz");
        assertThat(ProviderCheck.hostOf("  https://apigw.atmos.uz/partner  ")).isEqualTo("apigw.atmos.uz");
        // Credentials in a URL stay out of the host.
        assertThat(ProviderCheck.hostOf("https://user:pass@apigw.atmos.uz")).isEqualTo("apigw.atmos.uz");
        assertThat(ProviderCheck.hostOf("not a url")).isNull();
        assertThat(ProviderCheck.hostOf("")).isNull();
        assertThat(ProviderCheck.hostOf(null)).isNull();
    }

    // ---- configuration ----

    @Test
    void itRefusesToStartWithoutItsConfigurationAndSaysWhatIsMissing() {
        AppProperties empty = new AppProperties();
        empty.getAtmos().setBaseUrl(BASE);
        AtmosPaymentProvider unconfigured = providerWith(empty, RestClient.builder());

        assertThatThrownBy(unconfigured::requireConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ATMOS_CONSUMER_KEY")
                .hasMessageContaining("ATMOS_CONSUMER_SECRET")
                .hasMessageContaining("ATMOS_STORE_ID")
                .hasMessageNotContaining("ATMOS_BASE_URL");
    }

    /** Whitespace is trimmed on load, so a value that is only whitespace is a missing one. */
    @Test
    void aValueOfNothingButWhitespaceCountsAsMissing() {
        AppProperties padded = new AppProperties();
        padded.getAtmos().setBaseUrl(BASE);
        padded.getAtmos().setConsumerKey("  ");
        padded.getAtmos().setConsumerSecret("   ");
        padded.getAtmos().setStoreId("777");

        assertThat(padded.getAtmos().getConsumerKey()).isEmpty();
        assertThatThrownBy(() -> providerWith(padded, RestClient.builder())
                .requireConfiguration())
                .hasMessageContaining("ATMOS_CONSUMER_KEY")
                .hasMessageContaining("ATMOS_CONSUMER_SECRET")
                .hasMessageNotContaining("ATMOS_STORE_ID");
    }

    /**
     * A value pasted into the server's .env with a space around it is trimmed on load: the
     * base URL stays a URL and the credentials still make the same Basic auth header, rather
     * than coming back as an unexplained 401.
     */
    @Test
    void everyAtmosValueIsTrimmedOnLoad() {
        AppProperties padded = new AppProperties();
        AppProperties.Atmos atmos = padded.getAtmos();
        atmos.setBaseUrl("  " + BASE + "  ");
        atmos.setConsumerKey(" key ");
        atmos.setConsumerSecret("  secret  ");
        atmos.setStoreId(" 777 ");
        atmos.setPlatformRecipientId(" 900 ");
        atmos.setOfdCodeRental(" 10112001 ");
        atmos.setOfdCodeService(" 10112002 ");

        assertThat(atmos.getBaseUrl()).isEqualTo(BASE);
        assertThat(atmos.getStoreId()).isEqualTo("777");
        assertThat(atmos.getPlatformRecipientId()).isEqualTo("900");
        assertThat(atmos.getOfdCodeRental()).isEqualTo("10112001");
        assertThat(atmos.getOfdCodeService()).isEqualTo("10112002");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer paddedServer = MockRestServiceServer.bindTo(builder).build();
        AtmosPaymentProvider trimmed = providerWith(padded, builder);
        paddedServer.expect(once(), requestTo(TOKEN_URL))
                .andExpect(header("Authorization", "Basic a2V5OnNlY3JldA=="))
                .andRespond(withSuccess(TOKEN_ANSWER, MediaType.APPLICATION_JSON));
        paddedServer.expect(requestTo(BASE + "/partner/remove-card"))
                .andRespond(withSuccess("{" + OK + "}", MediaType.APPLICATION_JSON));

        trimmed.removeCard("1666711", "t1");

        paddedServer.verify();
    }

    /**
     * ATMOS_PLATFORM_RECIPIENT_ID is the one value a sandbox is given last, so it does not
     * stop the start-up: only the platform's own split of a team-game fee fails, and it says
     * which variable is missing. An ordinary booking takes nothing for the platform.
     */
    @Test
    void withoutThePlatformRecipientItStartsAndOnlyThePlatformSplitFails() {
        AppProperties noRecipient = new AppProperties();
        AppProperties.Atmos atmos = noRecipient.getAtmos();
        atmos.setBaseUrl(BASE);
        atmos.setConsumerKey("key");
        atmos.setConsumerSecret("secret");
        atmos.setStoreId("777");
        AtmosPaymentProvider provider = providerWith(noRecipient, RestClient.builder());

        assertThatCode(provider::requireConfiguration).doesNotThrowAnyException();

        // Refused while the request is still being built, so nothing is ever sent.
        assertThatThrownBy(() -> provider.charge(charge()))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("ATMOS_PLATFORM_RECIPIENT_ID")
                .extracting(ex -> ((PaymentProviderException) ex).getCode())
                .isEqualTo(AtmosCodes.RECIPIENT_MISSING);
    }

    /** F2: production with no platform recipient would refuse every team game; it does not start. */
    @Test
    void productionDoesNotStartWithoutThePlatformRecipient() {
        properties.getAtmos().setPlatformRecipientId(" ");
        AtmosPaymentProvider prod = onProfiles(properties, RestClient.builder(), "prod", "atmos");

        assertThatThrownBy(prod::requireConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ATMOS_PLATFORM_RECIPIENT_ID");
        // The sandbox server still starts, as before.
        assertThatCode(onProfiles(properties, RestClient.builder(), "staging", "atmos")::requireConfiguration)
                .doesNotThrowAnyException();
    }

    /**
     * F2/F7: a recipient we do not have is our own refusal, never the card's: both codes are
     * "misconfigured", so the player is told the service failed and the row gets no decline.
     */
    @Test
    void aMissingRecipientIsOursNeverACardDecline() {
        properties.getAtmos().setPlatformRecipientId(null);
        AtmosPaymentProvider prod = onProfiles(properties, RestClient.builder(), "prod", "atmos");

        assertThatThrownBy(() -> prod.charge(ChargeRequest.withToken(List.of(Split.owner(null, 5_000_000L)),
                "card-token", "0190c3a1-0000-7000-8000-000000000009")))
                .isInstanceOf(PaymentProviderException.class)
                .extracting(ex -> ((PaymentProviderException) ex).getCode())
                .isEqualTo(AtmosCodes.OWNER_NOT_CONNECTED);
        for (String code : new String[]{AtmosCodes.RECIPIENT_MISSING, AtmosCodes.OWNER_NOT_CONNECTED}) {
            assertThat(AtmosCodes.isMisconfigured(code)).isTrue();
            assertThat(uz.minipolya.minipolya_backend.provider.DeclineReason.of(code)).isNull();
        }
    }

    @Test
    void expiriesAndMasksAreConverted() {
        assertThat(AtmosPaymentProvider.toAtmosExpiry("09/27")).isEqualTo("2709");
        assertThat(AtmosPaymentProvider.fromAtmosExpiry("2505")).isEqualTo("05/25");
        assertThat(AtmosPaymentProvider.maskPan("986009******1840")).isEqualTo("9860 •••• •••• 1840");
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
