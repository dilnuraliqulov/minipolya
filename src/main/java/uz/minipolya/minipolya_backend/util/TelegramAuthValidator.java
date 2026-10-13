package uz.minipolya.minipolya_backend.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.exception.UnauthorizedException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verifies the HMAC-SHA256 signature of a Telegram Mini App {@code initData}
 * string, per the Telegram WebApp spec:
 *
 * <pre>
 *   secret_key = HMAC-SHA256(key = "WebAppData", data = bot_token)
 *   hash       = HMAC-SHA256(key = secret_key,  data = data_check_string)
 * </pre>
 *
 * where {@code data_check_string} is every field except {@code hash}, sorted by
 * key and joined as {@code key=value} with {@code \n}, using URL-decoded values.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAuthValidator {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public TelegramUserData validate(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new UnauthorizedException("Empty Telegram initData");
        }

        Map<String, String> params = parse(initData);

        String providedHash = params.remove("hash");
        if (providedHash == null || providedHash.isBlank()) {
            throw new UnauthorizedException("Telegram initData is missing hash");
        }

        String dataCheckString = buildDataCheckString(params);
        String expectedHash = computeHash(dataCheckString);

        if (!constantTimeEquals(expectedHash, providedHash)) {
            log.warn("Telegram initData signature mismatch");
            throw new UnauthorizedException("Invalid Telegram signature");
        }

        String userJson = params.get("user");
        if (userJson == null || userJson.isBlank()) {
            throw new UnauthorizedException("Telegram initData is missing user");
        }

        return parseUser(userJson);
    }

    private Map<String, String> parse(String initData) {
        // TreeMap keeps keys sorted, which is exactly what the data_check_string needs.
        Map<String, String> params = new TreeMap<>();
        for (String pair : initData.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private String buildDataCheckString(Map<String, String> sortedParams) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private String computeHash(String dataCheckString) {
        byte[] secretKey = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                botToken().getBytes(StandardCharsets.UTF_8));
        byte[] hash = hmac(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
        return toHex(hash);
    }

    private String botToken() {
        String token = appProperties.getBot().getToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("app.bot.token (BOT_TOKEN) is not configured");
        }
        return token;
    }

    private byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", ex);
        }
    }

    private TelegramUserData parseUser(String userJson) {
        try {
            JsonNode node = objectMapper.readTree(userJson);
            if (!node.hasNonNull("id")) {
                throw new UnauthorizedException("Telegram user has no id");
            }
            return new TelegramUserData(
                    node.get("id").asLong(),
                    text(node, "first_name"),
                    text(node, "last_name"),
                    text(node, "username"),
                    text(node, "language_code")
            );
        } catch (UnauthorizedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new UnauthorizedException("Malformed Telegram user payload");
        }
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
