package uz.minipolya.minipolya_backend.telegram;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** What the bot does with a private message or an inline-button press. */
@Component
public class TelegramBotFlow {

    public void onMessage(JsonNode message) {
        // Filled in by the /start, contact, /help and /language steps.
    }

    public void onCallbackQuery(JsonNode callbackQuery) {
        // Filled in by the /language step.
    }
}
