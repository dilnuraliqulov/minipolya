package uz.minipolya.minipolya_backend.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;

import java.util.Map;

/**
 * Receives Telegram bot updates (e.g. the /start deep link). Public — Telegram
 * calls it. Placeholder for now: accepts and logs the update.
 */
@Slf4j
@RestController
public class TelegramWebhookController {

    @PostMapping("/webhooks/telegram")
    public ApiResponse<Void> onUpdate(@RequestBody(required = false) Map<String, Object> update) {
        // TODO: Verify the Telegram secret token header, then handle commands (/start game_{id}, etc.).
        log.info("[telegram-webhook] update: {}", update);
        return ApiResponse.ok(null);
    }
}
