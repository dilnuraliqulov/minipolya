package uz.minipolya.minipolya_backend.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Receives Telegram bot updates in webhook mode. Public (no JWT): Telegram proves itself
 * with {@code X-Telegram-Bot-Api-Secret-Token}, which must equal TELEGRAM_WEBHOOK_SECRET,
 * compared in constant time. Anything else, and every call while no secret is configured,
 * is 403. The update itself goes to {@link TelegramUpdateHandler}, the same one polling uses.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TelegramWebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final AppProperties appProperties;
    private final TelegramUpdateHandler updateHandler;
    private final ObjectMapper json = JsonMapper.builder().build();

    @PostMapping("/webhooks/telegram")
    public ResponseEntity<ApiResponse<Void>> onUpdate(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) String body) {
        if (!secretMatches(secret)) {
            log.warn("[telegram] webhook call with a missing or wrong secret refused");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Forbidden"));
        }
        JsonNode update;
        try {
            update = json.readTree(body == null ? "" : body);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Malformed update"));
        }
        updateHandler.handle(update);
        // 200 once accepted, even if handling failed: Telegram would otherwise resend it forever.
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private boolean secretMatches(String given) {
        String expected = appProperties.getBot().getWebhookSecret();
        if (expected == null || expected.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
