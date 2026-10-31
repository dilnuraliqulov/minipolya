package uz.minipolya.minipolya_backend.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.service.AuthService;
import uz.minipolya.minipolya_backend.service.TelegramBotService;
import uz.minipolya.minipolya_backend.util.TelegramUserData;

import java.util.List;
import java.util.Map;

/**
 * What the bot does with a private message or an inline-button press. Every user is found
 * or created through {@link AuthService#findOrCreateFromTelegram}, the same code as the Mini
 * App sign-in, and spoken to in their saved {@code user.language}. A blocked user gets only
 * the blocked message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBotFlow {

    private final AuthService authService;
    private final TelegramBotService bot;
    private final TelegramBanner banner;
    private final BotTexts texts;
    private final StartPayloads startPayloads;
    private final AppProperties appProperties;

    public void onMessage(JsonNode message) {
        JsonNode from = message.path("from");
        if (!from.path("id").canConvertToLong() || from.path("is_bot").asBoolean(false)) {
            return;
        }
        long chatId = message.path("chat").path("id").asLong();
        User user = authService.findOrCreateFromTelegram(userData(from));
        String language = BotTexts.language(user.getLanguage());

        if (user.isBlocked()) {
            bot.send(chatId, blocked(language), TelegramKeyboards.removeKeyboard());
            return;
        }

        String text = message.path("text").asString("").trim();
        String command = command(text);
        if ("start".equals(command)) {
            start(chatId, user, from, language, argument(text));
        }
    }

    public void onCallbackQuery(JsonNode callbackQuery) {
        // Filled in by the /language step.
    }

    // ---- /start ----

    private void start(long chatId, User user, JsonNode from, String language, String payload) {
        String startParam = StartPayloads.valid(payload).orElse(null);
        String firstName = TelegramHtml.escape(firstName(user, from));
        if (user.getPhone() == null) {
            if (startParam != null) {
                startPayloads.remember(user.getTelegramId(), startParam);
            }
            banner.send(chatId, texts.get(language, "start.ask_phone", "firstName", firstName), askPhoneKeyboard(language));
        } else {
            banner.send(chatId, texts.get(language, "start.welcome", "firstName", firstName),
                    openButton(language, startParam));
        }
    }

    // ---- Shared pieces ----

    private String blocked(String language) {
        return texts.get(language, "blocked") + supportLine(language);
    }

    /** "\n\nYordam uchun: @support", or nothing when SUPPORT_USERNAME is not set. */
    String supportLine(String language) {
        String support = appProperties.getBot().getSupportUsername();
        if (support == null || support.isBlank()) {
            return "";
        }
        String name = support.trim().startsWith("@") ? support.trim().substring(1) : support.trim();
        return "\n\n" + texts.get(language, "support", "support", TelegramHtml.escape(name));
    }

    Map<String, Object> askPhoneKeyboard(String language) {
        return TelegramKeyboards.requestContact(texts.get(language, "button.share_phone"),
                texts.get(language, "placeholder.share_phone"));
    }

    /** The inline "open MiniPolya" button, or none when MINIAPP_URL is not set. */
    Map<String, Object> openButton(String language, String startParam) {
        String url = miniAppUrl(startParam);
        if (url == null) {
            log.warn("[telegram] MINIAPP_URL is not set: no open button");
            return null;
        }
        return TelegramKeyboards.inline(List.of(TelegramKeyboards.webAppButton(texts.get(language, "button.open"), url)));
    }

    /**
     * MINIAPP_URL, with {@code startapp=<param>} added when /start carried a deep link: a
     * web_app button gets no start_param from Telegram, so the Mini App reads it from its URL.
     */
    String miniAppUrl(String startParam) {
        String base = appProperties.getBot().getMiniappUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        base = base.trim();
        if (startParam == null) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?") + "startapp=" + startParam;
    }

    /** The user's own first name when they set one, else Telegram's, else their display name. */
    private static String firstName(User user, JsonNode from) {
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            return user.getFirstName();
        }
        String telegram = from.path("first_name").asString("").trim();
        return telegram.isEmpty() ? user.getName() : telegram;
    }

    private static TelegramUserData userData(JsonNode from) {
        return new TelegramUserData(
                from.path("id").asLong(),
                textOrNull(from, "first_name"),
                textOrNull(from, "last_name"),
                textOrNull(from, "username"),
                textOrNull(from, "language_code"));
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asString("");
        return value.isEmpty() ? null : value;
    }

    /** "start" for "/start", "/start payload" and "/start@MiniPolyaBot"; null when not a command. */
    static String command(String text) {
        if (!text.startsWith("/")) {
            return null;
        }
        String first = text.split("\\s+", 2)[0].substring(1);
        int at = first.indexOf('@');
        return (at >= 0 ? first.substring(0, at) : first).toLowerCase();
    }

    /** What follows the command, e.g. the deep-link payload of "/start game_42"; null when none. */
    static String argument(String text) {
        String[] parts = text.split("\\s+", 2);
        return parts.length < 2 || parts[1].isBlank() ? null : parts[1].trim();
    }
}
