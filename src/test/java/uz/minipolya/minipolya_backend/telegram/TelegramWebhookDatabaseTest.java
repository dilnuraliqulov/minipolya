package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.repository.TelegramProcessedUpdateRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The webhook through the real security chain and database: public, secret-checked, idempotent. */
@DatabaseTest
class TelegramWebhookDatabaseTest {

    /** The secret {@code @DatabaseTest} configures. */
    private static final String SECRET = "test-webhook-secret-test-webhook-secret";
    private static final String UPDATE = "{\"update_id\":987654321,\"message\":{\"message_id\":1,"
            + "\"from\":{\"id\":42},\"chat\":{\"id\":42,\"type\":\"private\"},\"text\":\"/help\"}}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TelegramProcessedUpdateRepository processed;
    @MockitoBean
    private TelegramBotFlow flow;

    @Test
    void theSameUpdateDeliveredTwiceIsHandledOnce() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/webhooks/telegram").header(TelegramWebhookController.SECRET_HEADER, SECRET)
                            .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                    .andExpect(status().isOk());
        }
        verify(flow, times(1)).onMessage(any());
        assertThat(processed.existsById(987654321L)).isTrue();
    }

    @Test
    void noJwtIsNeededButTheSecretIs() throws Exception {
        mvc.perform(post("/webhooks/telegram").header(TelegramWebhookController.SECRET_HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isForbidden());
        verify(flow, never()).onMessage(any());
        assertThat(processed.existsById(987654321L)).isFalse();
    }
}
