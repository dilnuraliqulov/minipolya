package uz.minipolya.minipolya_backend.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.minipolya.minipolya_backend.config.AppProperties;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * The raw Telegram Bot API: one JSON POST per method, answering the {@code result} node.
 * Everything above it (TelegramBotService, the update handler, the startup setup) goes
 * through here, so tests mock this one class.
 *
 * <p>Nothing is ever logged here except method names and error codes: request and
 * response bodies carry message texts and phone numbers, and the URL carries the token.
 * When the bot is not active ({@code app.bot.enabled=false} or no token) no call is made
 * and {@link #isActive()} is false; callers check it first.
 */
@Slf4j
@Component
public class TelegramApi {

    private final AppProperties.Bot bot;
    private final RestClient http;
    private final ObjectMapper json = JsonMapper.builder().build();

    public TelegramApi(AppProperties appProperties) {
        this.bot = appProperties.getBot();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        // Above the long-polling timeout (TelegramPolling.POLL_TIMEOUT_SECONDS).
        factory.setReadTimeout(Duration.ofSeconds(40));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isActive() {
        return bot.isActive();
    }

    /** Calls {@code method} with a JSON body; answers its {@code result}. */
    public JsonNode call(String method, Map<String, ?> params) {
        requireActive(method);
        try {
            String body = http.post()
                    .uri(url(method))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(params))
                    .retrieve()
                    .body(String.class);
            return result(method, body);
        } catch (RestClientResponseException ex) {
            return result(method, ex.getResponseBodyAsString());
        } catch (RestClientException ex) {
            // The exception's message names the URL, and the URL holds the token: drop it.
            throw new TelegramApiException(method, 0, "Telegram unreachable (" + ex.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Calls {@code method} as multipart, uploading {@code bytes} as the field
     * {@code fileField}. Any other value that is not a string (a reply_markup) is sent as JSON.
     */
    public JsonNode upload(String method, Map<String, ?> params, String fileField, byte[] bytes, String filename) {
        requireActive(method);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        params.forEach((key, value) -> form.add(key,
                value instanceof String || value instanceof Number || value instanceof Boolean
                        ? String.valueOf(value)
                        : json.writeValueAsString(value)));
        form.add(fileField, new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        try {
            String body = http.post()
                    .uri(url(method))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return result(method, body);
        } catch (RestClientResponseException ex) {
            return result(method, ex.getResponseBodyAsString());
        } catch (RestClientException ex) {
            throw new TelegramApiException(method, 0, "Telegram unreachable (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private void requireActive(String method) {
        if (!isActive()) {
            throw new TelegramApiException(method, 0, "bot is not active (app.bot.enabled=false or no token)");
        }
    }

    private String url(String method) {
        String base = bot.getApiBaseUrl().endsWith("/") ? bot.getApiBaseUrl() : bot.getApiBaseUrl() + "/";
        return base + "bot" + bot.getToken() + "/" + method;
    }

    private JsonNode result(String method, String body) {
        JsonNode answer;
        try {
            answer = json.readTree(body == null ? "" : body);
        } catch (RuntimeException ex) {
            throw new TelegramApiException(method, 0, "unreadable answer");
        }
        if (answer == null || !answer.path("ok").asBoolean(false)) {
            int code = answer == null ? 0 : answer.path("error_code").asInt(0);
            String description = answer == null ? "" : answer.path("description").asString("");
            throw new TelegramApiException(method, code, description);
        }
        return answer.path("result");
    }
}
