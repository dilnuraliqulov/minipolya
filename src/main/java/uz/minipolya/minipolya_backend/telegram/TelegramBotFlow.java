package uz.minipolya.minipolya_backend.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.UpdateUserRequest;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.PhoneTakenException;
import uz.minipolya.minipolya_backend.service.AuthService;
import uz.minipolya.minipolya_backend.service.TelegramBotService;
import uz.minipolya.minipolya_backend.service.UserService;
import uz.minipolya.minipolya_backend.util.PhoneMask;
import uz.minipolya.minipolya_backend.util.TelegramUserData;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** callback_data of the /language buttons: "lang:uz", "lang:ru", "lang:en". */
    static final String LANGUAGE_CALLBACK = "lang:";

    private final AuthService authService;
    private final UserService userService;
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

        if (message.has("contact")) {
            contact(chatId, user, from, message.path("contact"), language);
            return;
        }

        String text = message.path("text").asString("").trim();
        String command = command(text);
        if ("start".equals(command)) {
            start(chatId, user, from, language, argument(text));
        } else if ("help".equals(command)) {
            bot.send(chatId, texts.get(language, "help") + supportLine(language), openButton(language, null));
        } else if ("language".equals(command)) {
            bot.send(chatId, texts.get(language, "language.choose"), languageButtons());
        } else if (user.getPhone() == null) {
            // Anything else before the phone: ask for it again, with the button.
            bot.send(chatId, texts.get(language, "contact.ask"), askPhoneKeyboard(language));
        } else {
            bot.send(chatId, texts.get(language, "contact.next"), openButton(language, null));
        }
    }

    /**
     * An inline-button press. Only the /language buttons ({@code lang:uz|ru|en}) exist: the
     * choice is saved as {@code user.language} through the same profile update as the Mini
     * App, and the message is edited to confirm it in the new language.
     */
    public void onCallbackQuery(JsonNode callbackQuery) {
        String callbackId = callbackQuery.path("id").asString("");
        JsonNode from = callbackQuery.path("from");
        JsonNode message = callbackQuery.path("message");
        String data = callbackQuery.path("data").asString("");
        if (!from.path("id").canConvertToLong() || !data.startsWith(LANGUAGE_CALLBACK)
                || !message.path("message_id").canConvertToLong()) {
            bot.answerCallbackQuery(callbackId);
            return;
        }
        long chatId = message.path("chat").path("id").asLong();
        long messageId = message.path("message_id").asLong();
        User user = authService.findOrCreateFromTelegram(userData(from));
        if (user.isBlocked()) {
            bot.answerCallbackQuery(callbackId);
            bot.editMessageText(chatId, messageId, blocked(BotTexts.language(user.getLanguage())), null);
            return;
        }
        String chosen = data.substring(LANGUAGE_CALLBACK.length());
        if (!BotTexts.LANGUAGES.contains(chosen)) {
            bot.answerCallbackQuery(callbackId);
            return;
        }
        userService.updateProfile(user.getId(), UpdateUserRequest.builder().language(chosen).build());
        bot.answerCallbackQuery(callbackId);
        bot.editMessageText(chatId, messageId, texts.get(chosen, "language.saved"), null);
    }

    /** Each language in its own name, so anyone can find theirs whatever the current one is. */
    static Map<String, Object> languageButtons() {
        return TelegramKeyboards.inline(List.of(
                TelegramKeyboards.callbackButton("🇺🇿 Oʻzbekcha", LANGUAGE_CALLBACK + "uz"),
                TelegramKeyboards.callbackButton("🇷🇺 Русский", LANGUAGE_CALLBACK + "ru"),
                TelegramKeyboards.callbackButton("🇬🇧 English", LANGUAGE_CALLBACK + "en")));
    }

    // ---- /start ----

    private void start(long chatId, User user, JsonNode from, String language, String payload) {
        String startParam = StartPayloads.valid(payload).orElse(null);
        String firstName = TelegramHtml.escape(firstName(user, from));
        if (user.getPhone() == null) {
            if (startParam != null) {
                startPayloads.remember(user.getTelegramId(), startParam);
            }
            banner.send(chatId, texts.get(language, "start.greeting", "firstName", firstName),
                    openButton(language, startParam));
            bot.send(chatId, texts.get(language, "start.ask_phone"), askPhoneKeyboard(language));
        } else {
            banner.send(chatId, texts.get(language, "start.welcome", "firstName", firstName),
                    openButton(language, startParam));
        }
    }

    // ---- The contact ----

    /**
     * A shared contact: only the sender's own (contact.user_id == from.id), only an Uzbek
     * number, and never one another account already has. Then saved, confirmed (masked) with
     * the keyboard removed, and followed by the open button — carrying a deep link that came
     * with an earlier /start.
     */
    private void contact(long chatId, User user, JsonNode from, JsonNode contact, String language) {
        long fromId = from.path("id").asLong();
        if (!contact.path("user_id").canConvertToLong() || contact.path("user_id").asLong() != fromId) {
            bot.send(chatId, texts.get(language, "contact.not_own"), askPhoneKeyboard(language));
            return;
        }
        String phone = uzbekPhone(contact.path("phone_number").asString("")).orElse(null);
        if (phone == null) {
            log.info("[telegram] user={} shared a non-Uzbek number", fromId);
            bot.send(chatId, texts.get(language, "contact.not_uzbek"), null);
            return;
        }
        try {
            userService.savePhoneFromBot(user.getId(), phone);
        } catch (PhoneTakenException ex) {
            log.warn("[telegram] user={} shared {}, which another account already has: not saved",
                    fromId, PhoneMask.mask(phone));
            bot.send(chatId, texts.get(language, "contact.taken") + supportLine(language), null);
            return;
        }
        log.info("[telegram] user={} saved phone {}", fromId, PhoneMask.mask(phone));
        bot.send(chatId, texts.get(language, "contact.saved", "phone", PhoneMask.mask(phone)),
                TelegramKeyboards.removeKeyboard());
        String startParam = startPayloads.take(fromId).orElse(null);
        bot.send(chatId, texts.get(language, "contact.next"), openButton(language, startParam));
    }

    /**
     * {@code +998XXXXXXXXX}, from however Telegram wrote the number ("998901234558",
     * "+998 90 123 45 58"); empty for any other country.
     */
    static Optional<String> uzbekPhone(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        return digits.length() == 12 && digits.startsWith("998") ? Optional.of("+" + digits) : Optional.empty();
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

    /** The inline "open MiniPolya" button, or none when MINIAPP_URL is missing or not https. */
    Map<String, Object> openButton(String language, String startParam) {
        String url = miniAppUrl(startParam);
        if (url == null) {
            log.warn("[telegram] MINIAPP_URL is missing or not https: no open button");
            return null;
        }
        return TelegramKeyboards.inline(List.of(TelegramKeyboards.webAppButton(texts.get(language, "button.open"), url)));
    }

    /**
     * MINIAPP_URL, with {@code startapp=<param>} added when /start carried a deep link: a
     * web_app button gets no start_param from Telegram, so the Mini App reads it from its URL.
     * Null when MINIAPP_URL cannot be used for a web_app button.
     */
    String miniAppUrl(String startParam) {
        String base = appProperties.getBot().getMiniappUrl();
        if (!MiniAppUrl.usable(base)) {
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
