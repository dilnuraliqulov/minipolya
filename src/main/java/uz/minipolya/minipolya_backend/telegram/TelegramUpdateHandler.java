package uz.minipolya.minipolya_backend.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.minipolya.minipolya_backend.repository.TelegramProcessedUpdateRepository;

/**
 * The one entry point for a Telegram update, from the webhook and from polling alike.
 *
 * <p>Idempotent by {@code update_id}: the id is recorded first, in its own transaction, and
 * only the call that recorded it goes on, so a redelivered update never runs twice. An
 * update whose handling then fails is not retried (Telegram would otherwise resend it
 * forever); the failure is logged.
 *
 * <p>Logs only the update id, its type and the Telegram user id: never a message's text,
 * a contact or a phone number, and of an unexpected exception only its class (a database
 * error can quote the row it refused).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramUpdateHandler {

    private final TelegramProcessedUpdateRepository processedUpdates;
    private final TelegramBotFlow flow;

    /** @return false when the update was skipped: a duplicate, or without an update_id. */
    public boolean handle(JsonNode update) {
        if (update == null || !update.path("update_id").canConvertToLong()) {
            log.warn("[telegram] update without update_id ignored");
            return false;
        }
        long updateId = update.path("update_id").asLong();
        if (processedUpdates.claim(updateId) == 0) {
            log.info("[telegram] update_id={} already handled, skipped", updateId);
            return false;
        }

        String type = type(update);
        JsonNode from = update.path(type).path("from");
        Long userId = from.path("id").canConvertToLong() ? from.path("id").asLong() : null;
        log.info("[telegram] update_id={} type={} user={}", updateId, type, userId);

        try {
            switch (type) {
                case "message" -> {
                    // The bot talks one to one only; a group or a channel gets nothing.
                    if ("private".equals(update.path("message").path("chat").path("type").asString(""))) {
                        flow.onMessage(update.path("message"));
                    }
                }
                case "callback_query" -> flow.onCallbackQuery(update.path("callback_query"));
                default -> {
                    // edited messages, my_chat_member, ...: nothing to do
                }
            }
        } catch (TelegramApiException ex) {
            log.warn("[telegram] update_id={} type={} user={}: {}", updateId, type, userId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("[telegram] update_id={} type={} user={} failed: {}", updateId, type, userId,
                    ex.getClass().getName());
        }
        return true;
    }

    /** The update's kind: the one field besides update_id that Telegram sets. */
    static String type(JsonNode update) {
        for (String name : update.propertyNames()) {
            if (!"update_id".equals(name)) {
                return name;
            }
        }
        return "unknown";
    }
}
