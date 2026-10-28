package uz.minipolya.minipolya_backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.minipolya.minipolya_backend.dto.request.AddCardRequest;
import uz.minipolya.minipolya_backend.dto.request.ConfirmCardRequest;
import uz.minipolya.minipolya_backend.provider.BoundCard;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.Split;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Card tokens are encrypted at rest, and no secret ever reaches a log line through toString. */
class CardTokenCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private CardTokenCipher cipher(String key, String... profiles) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setCardTokenKey(key);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new CardTokenCipher(properties, environment);
    }

    @Test
    void aTokenRoundTripsAndIsNeverStoredInTheClear() {
        CardTokenCipher cipher = cipher(KEY);

        String stored = cipher.encrypt("card-token-123");

        assertThat(stored).startsWith("v1:").doesNotContain("card-token-123");
        assertThat(cipher.decrypt(stored)).isEqualTo("card-token-123");
    }

    @Test
    void theSameTokenEncryptsDifferentlyEachTime() {
        CardTokenCipher cipher = cipher(KEY);

        assertThat(cipher.encrypt("t")).isNotEqualTo(cipher.encrypt("t"));
    }

    @Test
    void aTokenWrittenBeforeEncryptionIsReadAsItIs() {
        assertThat(cipher(KEY).decrypt("tok-legacy")).isEqualTo("tok-legacy");
    }

    @Test
    void theWrongKeyCannotReadItAndSaysNothingAboutTheValue() {
        String stored = cipher(KEY).encrypt("card-token-123");
        CardTokenCipher other = cipher(Base64.getEncoder().encodeToString(new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));

        assertThatThrownBy(() -> other.decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(stored);
    }

    @Test
    void realMoneyProfilesRequireAProperKey() {
        assertThatThrownBy(() -> cipher(null, "atmos")).hasMessageContaining("CARD_TOKEN_KEY is not set");
        assertThatThrownBy(() -> cipher("", "prod")).hasMessageContaining("CARD_TOKEN_KEY is not set");
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
    }

    @Test
    void developmentFallsBackToAFixedKey() {
        CardTokenCipher dev = cipher(null);

        assertThat(dev.decrypt(dev.encrypt("t"))).isEqualTo("t");
    }

    @Test
    void noRequestOrResultPrintsACardNumberExpiryCodeOrToken() {
        String everything = String.join(" ",
                AddCardRequest.builder().cardNumber("8600123412341234").expiry("12/29").build().toString(),
                ConfirmCardRequest.builder().bindingId("b1").smsCode("654321").build().toString(),
                new BoundCard("secret-token", "card-1", "8600 •••• 1234", "12/30").toString(),
                ChargeRequest.withToken(List.of(Split.platform(100)), "secret-token", "acc-1").toString(),
                new ChargeRequest(List.of(Split.platform(100)), null, "8600123412341234", "12/29", "acc-2").toString(),
                new HoldRequest(List.of(Split.platform(100)), "secret-token", "acc-3", 60).toString());
        AppProperties.Atmos atmos = new AppProperties.Atmos();
        atmos.setConsumerKey("consumer-key");
        atmos.setConsumerSecret("consumer-secret");
        AppProperties.Security security = new AppProperties.Security();
        security.setCardTokenKey(KEY);
        everything += " " + atmos + " " + security;

        assertThat(everything)
                .doesNotContain("8600123412341234")
                .doesNotContain("12/29")
                .doesNotContain("654321")
                .doesNotContain("secret-token")
                .doesNotContain("consumer-key")
                .doesNotContain("consumer-secret")
                .doesNotContain(KEY);
    }
}
