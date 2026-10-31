package uz.minipolya.minipolya_backend.provider.atmos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.HoldCancelledException;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.PendingCharge;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.provider.TransactionClosedException;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
class AtmosPaymentProviderTest {

    private static final String BASE = "https://apigw.test";
    private static final String TOKEN_ANSWER = """
            {"access_token":"tok-abc","scope":"am_application_scope default","token_type":"Bearer","expires_in":3600}
            """;
    private static final String OK = "\"result\":{\"code\":\"OK\",\"description\":\"Нет ошибок\"}";

    private final MovableClock clock = new MovableClock();
    private MockRestServiceServer server;
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
        provider = new AtmosPaymentProvider(properties, builder, clock);
    }

    private void expectToken() {
        server.expect(once(), requestTo(BASE + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic a2V5OnNlY3JldA=="))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(TOKEN_ANSWER, MediaType.APPLICATION_JSON));
    }

    private ChargeRequest charge() {
        return ChargeRequest.withToken(List.of(Split.owner("501", 20_304_569L), Split.platform(5_431L)),
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

        ChargeOutcome outcome = provider.confirmCharge(List.of("1001"), null);

        assertThat(outcome.status()).isEqualTo(ProviderStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("STPIMS-ERR-057");
    }

    @Test
    void aLostAnswerIsATimeoutAndGetChargeTellsWhatHappened() {
        expectToken();
        server.expect(requestTo(BASE + "/merchant/bulk/pay/apply")).andRespond(request -> {
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

    @Test
    void aHoldIsCreatedWithItsItemsAndAppliedWithTheTokenOtp() {
        expectToken();
        server.expect(requestTo(BASE + "/hold/multiple/create"))
                .andExpect(jsonPath("$.card_token").value("card-token"))
                .andExpect(jsonPath("$.duration").value("600"))
                .andExpect(jsonPath("$.items[0].store_id").value(501))
                .andExpect(jsonPath("$.items[0].amount").value("5076143"))
                .andRespond(withSuccess("{" + OK + ",\"parent_id\":3001,\"items\":[{\"hold_id\":3002,\"account\":\"a\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/hold/multiple/apply/3001"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.otp").value("111111"))
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

    // ---- configuration ----

    @Test
    void itRefusesToStartWithoutItsConfigurationAndSaysWhatIsMissing() {
        AppProperties empty = new AppProperties();
        empty.getAtmos().setBaseUrl(BASE);
        AtmosPaymentProvider unconfigured = new AtmosPaymentProvider(empty, RestClient.builder(), clock);

        assertThatThrownBy(unconfigured::requireConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ATMOS_CONSUMER_KEY")
                .hasMessageContaining("ATMOS_STORE_ID")
                .hasMessageContaining("ATMOS_PLATFORM_RECIPIENT_ID")
                .hasMessageNotContaining("ATMOS_BASE_URL");
    }

    @Test
    void expiriesAndMasksAreConverted() {
        assertThat(AtmosPaymentProvider.toAtmosExpiry("09/27")).isEqualTo("2709");
        assertThat(AtmosPaymentProvider.fromAtmosExpiry("2505")).isEqualTo("05/25");
        assertThat(AtmosPaymentProvider.maskPan("986009******1840")).isEqualTo("9860 •••• 1840");
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
