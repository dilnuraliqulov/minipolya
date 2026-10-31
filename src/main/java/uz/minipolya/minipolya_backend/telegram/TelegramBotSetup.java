package uz.minipolya.minipolya_backend.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.minipolya.minipolya_backend.config.AppProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings the bot's Telegram profile in line with {@link BotTexts} on startup: the description
 * shown before Start, the short description, the command list (uz as the default with no
 * language_code, then ru and en) and the menu button that opens the Mini App.
 *
 * <p>Idempotent and gentle on rate limits: each value is read first (getMyDescription, ...)
 * and set only when it differs, so a restart with nothing changed makes no set call at all.
 * Runs in the background after startup; a failure is logged and never stops the application.
 * Skipped when the bot is not active or {@code app.bot.setup-on-startup=false}.
 */
@Slf4j
@Component
public class TelegramBotSetup {

    static final String MENU_BUTTON_TEXT = "MiniPolya";

    private final TelegramApi api;
    private final BotTexts texts;
    private final AppProperties.Bot bot;

    public TelegramBotSetup(TelegramApi api, BotTexts texts, AppProperties appProperties) {
        this.api = api;
        this.texts = texts;
        this.bot = appProperties.getBot();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!bot.isSetupOnStartup() || !api.isActive()) {
            return;
        }
        Thread.ofVirtual().name("telegram-setup").start(this::run);
    }

    /** @return how many values were changed at Telegram. */
    public int run() {
        int changed = 0;
        for (String language : BotTexts.LANGUAGES) {
            changed += step("description " + language, () -> description(language));
            changed += step("short description " + language, () -> shortDescription(language));
            changed += step("commands " + language, () -> commands(language));
        }
        changed += step("menu button", this::menuButton);
        log.info("[telegram] bot setup done: {} value(s) changed", changed);
        return changed;
    }

    private boolean description(String language) {
        String wanted = texts.get(language, "description");
        String current = api.call("getMyDescription", languageParam(language)).path("description").asString("");
        if (wanted.equals(current)) {
            return false;
        }
        Map<String, Object> params = languageParam(language);
        params.put("description", wanted);
        api.call("setMyDescription", params);
        return true;
    }

    private boolean shortDescription(String language) {
        String wanted = texts.get(language, "short_description");
        String current = api.call("getMyShortDescription", languageParam(language))
                .path("short_description").asString("");
        if (wanted.equals(current)) {
            return false;
        }
        Map<String, Object> params = languageParam(language);
        params.put("short_description", wanted);
        api.call("setMyShortDescription", params);
        return true;
    }

    private boolean commands(String language) {
        List<Map<String, String>> wanted = List.of(
                command("start", texts.get(language, "command.start")),
                command("help", texts.get(language, "command.help")),
                command("language", texts.get(language, "command.language")));
        List<Map<String, String>> current = new ArrayList<>();
        for (JsonNode node : api.call("getMyCommands", languageParam(language))) {
            current.add(command(node.path("command").asString(""), node.path("description").asString("")));
        }
        if (wanted.equals(current)) {
            return false;
        }
        Map<String, Object> params = languageParam(language);
        params.put("commands", wanted);
        api.call("setMyCommands", params);
        return true;
    }

    private boolean menuButton() {
        String url = bot.getMiniappUrl();
        if (url == null || url.isBlank()) {
            log.warn("[telegram] MINIAPP_URL is not set: the menu button is left as it is");
            return false;
        }
        JsonNode current = api.call("getChatMenuButton", Map.of());
        if ("web_app".equals(current.path("type").asString(""))
                && MENU_BUTTON_TEXT.equals(current.path("text").asString(""))
                && url.equals(current.path("web_app").path("url").asString(""))) {
            return false;
        }
        api.call("setChatMenuButton", Map.of("menu_button",
                Map.of("type", "web_app", "text", MENU_BUTTON_TEXT, "web_app", Map.of("url", url))));
        return true;
    }

    /** uz is the default for every language Telegram has no texts for: it goes without language_code. */
    private static Map<String, Object> languageParam(String language) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (!BotTexts.DEFAULT_LANGUAGE.equals(language)) {
            params.put("language_code", language);
        }
        return params;
    }

    private static Map<String, String> command(String command, String description) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("command", command);
        map.put("description", description);
        return map;
    }

    private interface Step {
        boolean apply();
    }

    private int step(String what, Step step) {
        try {
            return step.apply() ? 1 : 0;
        } catch (TelegramApiException ex) {
            log.warn("[telegram] setup of {} failed: {}", what, ex.getMessage());
            return 0;
        } catch (RuntimeException ex) {
            log.warn("[telegram] setup of {} failed: {}", what, ex.getClass().getName());
            return 0;
        }
    }
}
