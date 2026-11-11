package uz.minipolya.minipolya_backend.telegram;

import lombok.Getter;

/**
 * A Bot API call that failed: Telegram answered {@code ok: false} ({@link #getErrorCode()}
 * is its error_code, e.g. 400 or 403 when the user blocked the bot), or it could not be
 * reached ({@code errorCode} 0). The message never carries the request URL, which holds
 * the bot token.
 */
@Getter
public class TelegramApiException extends RuntimeException {

    private final int errorCode;

    public TelegramApiException(String method, int errorCode, String description) {
        super(method + " failed: " + errorCode + " " + description);
        this.errorCode = errorCode;
    }
}
