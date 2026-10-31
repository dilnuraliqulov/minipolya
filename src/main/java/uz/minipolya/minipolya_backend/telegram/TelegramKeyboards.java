package uz.minipolya.minipolya_backend.telegram;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** reply_markup objects for the Bot API, as maps that serialise to its JSON. */
public final class TelegramKeyboards {

    private TelegramKeyboards() {
    }

    /** An inline keyboard; each argument is one row. */
    @SafeVarargs
    public static Map<String, Object> inline(List<Map<String, Object>>... rows) {
        return Map.of("inline_keyboard", Arrays.asList(rows));
    }

    /** A button that opens the Mini App inside Telegram. */
    public static Map<String, Object> webAppButton(String text, String url) {
        return Map.of("text", text, "web_app", Map.of("url", url));
    }

    public static Map<String, Object> urlButton(String text, String url) {
        return Map.of("text", text, "url", url);
    }

    public static Map<String, Object> callbackButton(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    /** The one-button reply keyboard that shares the user's own phone number. */
    public static Map<String, Object> requestContact(String buttonText, String placeholder) {
        Map<String, Object> keyboard = new LinkedHashMap<>();
        keyboard.put("keyboard", List.of(List.of(Map.of("text", buttonText, "request_contact", true))));
        keyboard.put("resize_keyboard", true);
        keyboard.put("one_time_keyboard", true);
        keyboard.put("input_field_placeholder", placeholder);
        return keyboard;
    }

    public static Map<String, Object> removeKeyboard() {
        return Map.of("remove_keyboard", true);
    }
}
