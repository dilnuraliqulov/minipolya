package uz.minipolya.minipolya_backend.telegram;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.TelegramBotValue;
import uz.minipolya.minipolya_backend.repository.TelegramBotValueRepository;
import uz.minipolya.minipolya_backend.service.AuthService;
import uz.minipolya.minipolya_backend.service.TelegramBotService;
import uz.minipolya.minipolya_backend.service.UserService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * The bot's flow over a mocked Telegram API: the real TelegramBotService, BotTexts, banner
 * and start payloads, with the services given by the test (mocks, or real ones).
 * Records every Bot API call so a test can read what the user was sent.
 */
final class BotFixture {

    static final String MINIAPP = "https://app.minipolya.uz";
    static final ObjectMapper JSON = JsonMapper.builder().build();

    /** One Bot API call: the method and its parameters. */
    record Call(String method, Map<String, Object> params) {
        String text() {
            Object text = params.containsKey("text") ? params.get("text") : params.get("caption");
            return text == null ? null : text.toString();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> markup() {
            return (Map<String, Object>) params.get("reply_markup");
        }

        /** The url of the first inline web_app button, or null. */
        @SuppressWarnings("unchecked")
        String webAppUrl() {
            Map<String, Object> markup = markup();
            if (markup == null || !markup.containsKey("inline_keyboard")) {
                return null;
            }
            List<List<Map<String, Object>>> rows = (List<List<Map<String, Object>>>) markup.get("inline_keyboard");
            Map<String, Object> webApp = (Map<String, Object>) rows.getFirst().getFirst().get("web_app");
            return webApp == null ? null : webApp.get("url").toString();
        }

        boolean asksForContact() {
            return markup() != null && markup().toString().contains("request_contact=true");
        }

        boolean removesKeyboard() {
            return markup() != null && Boolean.TRUE.equals(markup().get("remove_keyboard"));
        }
    }

    final AppProperties properties = new AppProperties();
    final TelegramApi api = mock(TelegramApi.class);
    final TelegramBotValueRepository values = mock(TelegramBotValueRepository.class);
    final Map<String, TelegramBotValue> stored = new HashMap<>();
    final BotTexts texts = new BotTexts();
    final TelegramBotService botService = new TelegramBotService(api);
    final TelegramBanner banner = new TelegramBanner(api, botService, values);
    final StartPayloads startPayloads = new StartPayloads(Clock.systemUTC());

    private long nextMessageId = 100;

    BotFixture() {
        properties.getBot().setMiniappUrl(MINIAPP);
        when(api.isActive()).thenReturn(true);
        when(values.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<String>getArgument(0))));
        when(values.save(any(TelegramBotValue.class))).thenAnswer(inv -> {
            TelegramBotValue value = inv.getArgument(0);
            stored.put(value.getKey(), value);
            return value;
        });
        when(api.call(anyString(), anyMap())).thenAnswer(inv -> JSON.valueToTree(Map.of("message_id", nextMessageId++)));
        when(api.upload(eq("sendPhoto"), anyMap(), eq("photo"), any(byte[].class), anyString()))
                .thenAnswer(inv -> JSON.valueToTree(Map.of("message_id", nextMessageId++,
                        "photo", List.of(Map.of("file_id", "small"), Map.of("file_id", "BANNER-FILE-ID")))));
    }

    TelegramBotFlow flow(AuthService authService, UserService userService) {
        return new TelegramBotFlow(authService, userService, botService, banner, texts, startPayloads, properties);
    }

    /** Every Bot API call so far, in order (uploads included), sends only. */
    @SuppressWarnings("unchecked")
    List<Call> sent() {
        List<Call> calls = new ArrayList<>();
        mockingDetails(api).getInvocations().forEach(inv -> {
            String name = inv.getMethod().getName();
            if (name.equals("call") || name.equals("upload")) {
                calls.add(new Call(inv.getArgument(0), (Map<String, Object>) inv.getArgument(1)));
            }
        });
        return calls;
    }

    Call last() {
        List<Call> calls = sent();
        return calls.getLast();
    }

    static JsonNode message(long userId, String firstName, String text) {
        Map<String, Object> message = new HashMap<>();
        message.put("message_id", 1);
        message.put("from", Map.of("id", userId, "is_bot", false, "first_name", firstName));
        message.put("chat", Map.of("id", userId, "type", "private"));
        message.put("text", text);
        return JSON.valueToTree(message);
    }

    static JsonNode contact(long fromId, Long contactUserId, String phone) {
        Map<String, Object> contact = new HashMap<>();
        contact.put("phone_number", phone);
        contact.put("first_name", "Ali");
        if (contactUserId != null) {
            contact.put("user_id", contactUserId);
        }
        return JSON.valueToTree(Map.of(
                "message_id", 2,
                "from", Map.of("id", fromId, "is_bot", false, "first_name", "Ali"),
                "chat", Map.of("id", fromId, "type", "private"),
                "contact", contact));
    }

    static JsonNode callback(long userId, long messageId, String data) {
        return JSON.valueToTree(Map.of(
                "id", "cbq-1",
                "from", Map.of("id", userId, "is_bot", false, "first_name", "Ali"),
                "message", Map.of("message_id", messageId, "chat", Map.of("id", userId, "type", "private")),
                "data", data));
    }
}
