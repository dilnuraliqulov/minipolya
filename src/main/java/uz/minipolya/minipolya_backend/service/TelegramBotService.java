package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import uz.minipolya.minipolya_backend.telegram.TelegramApi;
import uz.minipolya.minipolya_backend.telegram.TelegramApiException;
import uz.minipolya.minipolya_backend.telegram.TelegramKeyboards;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends messages to Telegram users through the Bot API ({@link TelegramApi}), as HTML. Used
 * by the notifications and by the bot's own replies. Never logs a message's text — only the
 * method and the chat id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {

    private final TelegramApi telegramApi;

    /** @return true if Telegram accepted the message; false when the bot is off or the call failed. */
    public boolean sendMessage(Long telegramChatId, String html) {
        return trySend(telegramChatId, html, null);
    }

    /** Sends a message with a single inline URL button (e.g. a deep link). */
    public boolean sendMessageWithButton(Long telegramChatId, String html, String buttonText, String buttonUrl) {
        return trySend(telegramChatId, html,
                TelegramKeyboards.inline(List.of(TelegramKeyboards.urlButton(buttonText, buttonUrl))));
    }

    /**
     * Sends an HTML message with an optional reply_markup. Throws {@link TelegramApiException}
     * when Telegram refuses it — the bot's own replies want to know.
     */
    public JsonNode send(long chatId, String html, Object replyMarkup) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("text", html);
        params.put("parse_mode", "HTML");
        params.put("link_preview_options", Map.of("is_disabled", true));
        if (replyMarkup != null) {
            params.put("reply_markup", replyMarkup);
        }
        return telegramApi.call("sendMessage", params);
    }

    /** Replaces the text (and inline keyboard) of a message the bot sent. */
    public void editMessageText(long chatId, long messageId, String html, Object inlineMarkup) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        params.put("text", html);
        params.put("parse_mode", "HTML");
        if (inlineMarkup != null) {
            params.put("reply_markup", inlineMarkup);
        }
        telegramApi.call("editMessageText", params);
    }

    /** Stops the spinner on an inline button. Best effort: a late answer is not an error worth reporting. */
    public void answerCallbackQuery(String callbackQueryId) {
        try {
            telegramApi.call("answerCallbackQuery", Map.of("callback_query_id", callbackQueryId));
        } catch (TelegramApiException ex) {
            log.debug("[telegram] answerCallbackQuery: {}", ex.getMessage());
        }
    }

    private boolean trySend(Long chatId, String html, Object replyMarkup) {
        if (chatId == null || !telegramApi.isActive()) {
            log.info("[telegram] bot inactive, not sent to chat {}", chatId);
            return false;
        }
        try {
            send(chatId, html, replyMarkup);
            return true;
        } catch (TelegramApiException ex) {
            log.warn("[telegram] sendMessage to chat {}: {}", chatId, ex.getMessage());
            return false;
        }
    }
}
