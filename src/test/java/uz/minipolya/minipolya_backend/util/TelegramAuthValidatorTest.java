package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.exception.UnauthorizedException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramAuthValidatorTest {

    private static final String BOT_TOKEN = "123456:TEST-BOT-TOKEN";
    private static final String USER_JSON =
            "{\"id\":777,\"first_name\":\"Ali\",\"last_name\":\"Valiyev\",\"username\":\"ali\",\"language_code\":\"ru\"}";

    private TelegramAuthValidator validator;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getBot().setToken(BOT_TOKEN);
        validator = new TelegramAuthValidator(props, new ObjectMapper());
    }

    @Test
    void acceptsCorrectlySignedInitData() {
        String initData = signedInitData(BOT_TOKEN);

        TelegramUserData data = validator.validate(initData);

        assertThat(data.telegramId()).isEqualTo(777L);
        assertThat(data.displayName()).isEqualTo("Ali Valiyev");
        assertThat(data.username()).isEqualTo("ali");
        assertThat(data.languageCode()).isEqualTo("ru");
    }

    @Test
    void rejectsTamperedHash() {
        String initData = signedInitData(BOT_TOKEN).replaceAll("hash=[0-9a-f]+", "hash=deadbeef");

        assertThatThrownBy(() -> validator.validate(initData))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsSignatureFromAnotherBotToken() {
        String initData = signedInitData("999999:SOME-OTHER-TOKEN");

        assertThatThrownBy(() -> validator.validate(initData))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsBlankInitData() {
        assertThatThrownBy(() -> validator.validate("  "))
                .isInstanceOf(UnauthorizedException.class);
    }

    // ---- helpers: reproduce Telegram's signing so we can craft valid fixtures ----

    private String signedInitData(String botToken) {
        String authDate = "1700000000";
        String queryId = "AAHdF6IQAAAAAN0XohDhrOrc";

        // data_check_string uses RAW (decoded) values, keys sorted ascending.
        String dataCheckString = "auth_date=" + authDate
                + "\nquery_id=" + queryId
                + "\nuser=" + USER_JSON;

        byte[] secretKey = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                botToken.getBytes(StandardCharsets.UTF_8));
        String hash = toHex(hmac(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8)));

        return "query_id=" + enc(queryId)
                + "&user=" + enc(USER_JSON)
                + "&auth_date=" + authDate
                + "&hash=" + hash;
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
