package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import uz.minipolya.minipolya_backend.config.AppProperties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TelegramWebhookControllerTest {

    private static final String SECRET = "s3cret-s3cret-s3cret-s3cret-s3cret-42";
    private static final String UPDATE = "{\"update_id\":10,\"message\":{\"text\":\"/start\"}}";

    private final AppProperties properties = new AppProperties();
    private final TelegramUpdateHandler handler = mock(TelegramUpdateHandler.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties.getBot().setWebhookSecret(SECRET);
        mvc = MockMvcBuilders.standaloneSetup(new TelegramWebhookController(properties, handler)).build();
    }

    @Test
    void theRightSecretIsHandled() throws Exception {
        mvc.perform(post("/webhooks/telegram").header(TelegramWebhookController.SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isOk());
        verify(handler).handle(any(JsonNode.class));
    }

    @Test
    void aWrongSecretIs403AndNotHandled() throws Exception {
        mvc.perform(post("/webhooks/telegram").header(TelegramWebhookController.SECRET_HEADER, SECRET + "x")
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isForbidden());
        verify(handler, never()).handle(any());
    }

    @Test
    void noSecretHeaderIs403() throws Exception {
        mvc.perform(post("/webhooks/telegram").contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isForbidden());
        verify(handler, never()).handle(any());
    }

    @Test
    void withNoSecretConfiguredEveryCallIs403() throws Exception {
        properties.getBot().setWebhookSecret("");
        mvc.perform(post("/webhooks/telegram").header(TelegramWebhookController.SECRET_HEADER, "")
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isForbidden());
        verify(handler, never()).handle(any());
    }
}
