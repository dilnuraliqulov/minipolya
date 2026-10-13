package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.minipolya.minipolya_backend.config.AppProperties;

/**
 * Sends messages to Telegram users. Mock for now — logs instead of calling the
 * Telegram API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {

    private final AppProperties appProperties;

    /** @return true if the message was accepted for delivery. */
    public boolean sendMessage(Long telegramChatId, String text) {
        // TODO: Implement real Telegram Bot API call:
        //   POST https://api.telegram.org/bot{appProperties.getBot().getToken()}/sendMessage
        //   body: { chat_id, text, parse_mode: "HTML" }
        log.info("[telegram-mock] -> chat {}:\n{}", telegramChatId, text);
        return true;
    }

    /** Sends a message with a single inline button (e.g. a deep link into the Mini App). */
    public boolean sendMessageWithButton(Long telegramChatId, String text, String buttonText, String buttonUrl) {
        // TODO: Implement real Telegram Bot API call with reply_markup inline_keyboard.
        log.info("[telegram-mock] -> chat {} [button '{}' -> {}]:\n{}", telegramChatId, buttonText, buttonUrl, text);
        return true;
    }
}
