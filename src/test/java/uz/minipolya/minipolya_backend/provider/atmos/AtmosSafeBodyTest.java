package uz.minipolya.minipolya_backend.provider.atmos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response bodies may now be logged, so this is the test that the hard rule still holds:
 * card numbers, expiry dates, SMS codes, card tokens and phone numbers never reach the log,
 * whichever ATMOS answer they arrive in.
 */
class AtmosSafeBodyTest {

    /** The bind-card/confirm answer: the one body in the whole API with a card in it. */
    @Test
    void aBoundCardIsRedactedFieldByField() {
        String body = """
                {"result":{"code":"OK","description":"Нет ошибок"},
                 "data":{"card_id":1579076,"pan":"986009******1840","expiry":"2505",
                 "card_holder":"TEST","balance":1000000000,"phone":"********9999",
                 "card_token":"abcdef-the-token"},"transaction_id":4789}""";

        String safe = AtmosSafeBody.of(body);

        assertThat(safe)
                .doesNotContain("abcdef-the-token")
                .doesNotContain("986009")
                .doesNotContain("2505")
                .doesNotContain("9999")
                .doesNotContain("TEST")
                .doesNotContain("1579076");
        // What is left is what the log is for.
        assertThat(safe).contains("\"code\":\"OK\"").contains("\"transaction_id\":4789")
                .contains("\"balance\":1000000000");
    }

    /** A hold capture answers the card token under the name {@code card_id}. */
    @Test
    void theCardTokenIsRedactedEvenWhenItIsCalledCardId() {
        String safe = AtmosSafeBody.of("{\"store_transaction\":{\"card_id\":\"tok-123\",\"amount\":5000000}}");

        assertThat(safe).doesNotContain("tok-123").contains("\"amount\":5000000");
    }

    @Test
    void anOtpIsNeverLogged() {
        assertThat(AtmosSafeBody.of("{\"transaction_id\":383,\"otp\":\"111111\"}"))
                .doesNotContain("111111")
                .contains("\"transaction_id\":383");
    }

    @Test
    void anAccessTokenIsNeverLogged() {
        assertThat(AtmosSafeBody.of("{\"access_token\":\"ey.secret\",\"expires_in\":3600}"))
                .doesNotContain("ey.secret")
                .contains("3600");
    }

    /** A gateway fault is the whole reason bodies are logged, so it comes through intact. */
    @Test
    void aGatewayFaultComesThroughAsItIs() {
        String safe = AtmosSafeBody.of("""
                {"fault":{"code":900908,"message":"Resource forbidden",
                 "description":"Access failure for API: /hold"}}""");

        assertThat(safe).contains("900908").contains("Resource forbidden").contains("/hold")
                .doesNotContain("\n");
    }

    /** An HTML error page in place of JSON must not fill the log. */
    @Test
    void aLongBodyIsCutOff() {
        String safe = AtmosSafeBody.of("<html>" + "x".repeat(5_000) + "</html>");

        assertThat(safe).hasSize(AtmosSafeBody.MAX + "…(cut)".length()).endsWith("…(cut)");
    }

    @Test
    void nothingAtAllSaysSo() {
        assertThat(AtmosSafeBody.of(null)).isEqualTo("<empty>");
        assertThat(AtmosSafeBody.of("  ")).isEqualTo("<empty>");
    }
}
