package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.minipolya.minipolya_backend.config.AppProperties;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramBotSetupTest {

    private static final String URL = "https://app.minipolya.uz";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final TelegramApi api = mock(TelegramApi.class);
    private final BotTexts texts = new BotTexts();
    private final AppProperties properties = new AppProperties();
    private TelegramBotSetup setup;

    @BeforeEach
    void setUp() {
        properties.getBot().setMiniappUrl(URL);
        setup = new TelegramBotSetup(api, texts, properties);
    }

    private static String lang(Map<?, ?> params) {
        Object code = params.get("language_code");
        return code == null ? "uz" : code.toString();
    }

    /** Telegram already holds exactly what BotTexts says, for every language. */
    private void telegramAlreadyUpToDate() {
        when(api.call(eq("getMyDescription"), anyMap())).thenAnswer(inv -> JSON.valueToTree(
                Map.of("description", texts.get(lang(inv.getArgument(1)), "description"))));
        when(api.call(eq("getMyShortDescription"), anyMap())).thenAnswer(inv -> JSON.valueToTree(
                Map.of("short_description", texts.get(lang(inv.getArgument(1)), "short_description"))));
        when(api.call(eq("getMyCommands"), anyMap())).thenAnswer(inv -> {
            String language = lang(inv.getArgument(1));
            return JSON.valueToTree(List.of(
                    Map.of("command", "start", "description", texts.get(language, "command.start")),
                    Map.of("command", "help", "description", texts.get(language, "command.help")),
                    Map.of("command", "language", "description", texts.get(language, "command.language"))));
        });
        when(api.call(eq("getChatMenuButton"), anyMap())).thenReturn(JSON.valueToTree(
                Map.of("type", "web_app", "text", "MiniPolya", "web_app", Map.of("url", URL))));
    }

    @Test
    void nothingIsSetWhenTelegramAlreadyHasTheValues() {
        telegramAlreadyUpToDate();

        assertThat(setup.run()).isZero();

        verify(api, never()).call(startsWith("set"), any());
    }

    @Test
    void onlyTheValuesThatDifferAreSet() {
        telegramAlreadyUpToDate();
        // The Russian description is out of date, and the menu button is still the default one.
        when(api.call(eq("getMyDescription"), argThat(p -> "ru".equals(p.get("language_code")))))
                .thenReturn(JSON.valueToTree(Map.of("description", "old")));
        when(api.call(eq("getChatMenuButton"), anyMap())).thenReturn(JSON.valueToTree(Map.of("type", "commands")));

        assertThat(setup.run()).isEqualTo(2);

        verify(api).call("setMyDescription", Map.of("language_code", "ru", "description", texts.get("ru", "description")));
        verify(api).call("setChatMenuButton", Map.of("menu_button",
                Map.of("type", "web_app", "text", "MiniPolya", "web_app", Map.of("url", URL))));
        verify(api, never()).call(eq("setMyShortDescription"), any());
        verify(api, never()).call(eq("setMyCommands"), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFreshBotGetsEverythingWithUzAsTheDefaultLanguage() {
        JsonNode empty = JSON.valueToTree(Map.of());
        when(api.call(eq("getMyDescription"), anyMap())).thenReturn(JSON.valueToTree(Map.of("description", "")));
        when(api.call(eq("getMyShortDescription"), anyMap())).thenReturn(JSON.valueToTree(Map.of("short_description", "")));
        when(api.call(eq("getMyCommands"), anyMap())).thenReturn(JSON.valueToTree(List.of()));
        when(api.call(eq("getChatMenuButton"), anyMap())).thenReturn(empty);

        assertThat(setup.run()).isEqualTo(3 * 3 + 1);

        // uz goes without language_code, so it is what every other language sees.
        ArgumentCaptor<Map<String, ?>> params = ArgumentCaptor.forClass(Map.class);
        verify(api, org.mockito.Mockito.times(3)).call(eq("setMyCommands"), params.capture());
        Map<String, ?> uz = params.getAllValues().getFirst();
        assertThat(uz).doesNotContainKey("language_code");
        assertThat((List<Map<String, String>>) uz.get("commands")).containsExactly(
                Map.of("command", "start", "description", "Boshlash"),
                Map.of("command", "help", "description", "Yordam"),
                Map.of("command", "language", "description", "Tilni oʻzgartirish"));
    }

    @Test
    void aFailingCallIsLoggedAndTheRestStillRuns() {
        telegramAlreadyUpToDate();
        when(api.call(eq("getMyDescription"), anyMap())).thenThrow(new TelegramApiException("getMyDescription", 429, "Too Many Requests"));

        assertThat(setup.run()).isZero();
        verify(api).call(eq("getChatMenuButton"), anyMap());
    }

    @Test
    void withoutAMiniAppUrlTheMenuButtonIsLeftAlone() {
        telegramAlreadyUpToDate();
        properties.getBot().setMiniappUrl("");

        setup.run();

        verify(api, never()).call(eq("getChatMenuButton"), any());
        verify(api, never()).call(eq("setChatMenuButton"), any());
    }
}
