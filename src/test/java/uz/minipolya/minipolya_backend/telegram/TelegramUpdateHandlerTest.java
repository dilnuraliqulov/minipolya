package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.minipolya.minipolya_backend.repository.TelegramProcessedUpdateRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramUpdateHandlerTest {

    private final TelegramProcessedUpdateRepository processed = mock(TelegramProcessedUpdateRepository.class);
    private final TelegramBotFlow flow = mock(TelegramBotFlow.class);
    private final TelegramUpdateHandler handler = new TelegramUpdateHandler(processed, flow);

    static JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
    }

    private static JsonNode privateMessage(long updateId) {
        return json("{\"update_id\":" + updateId + ",\"message\":{\"message_id\":1,"
                + "\"from\":{\"id\":42,\"first_name\":\"Ali\"},\"chat\":{\"id\":42,\"type\":\"private\"},\"text\":\"/start\"}}");
    }

    @Test
    void aDuplicateUpdateIdRunsOnlyOnce() {
        when(processed.claim(7L)).thenReturn(1).thenReturn(0);

        assertThat(handler.handle(privateMessage(7))).isTrue();
        assertThat(handler.handle(privateMessage(7))).isFalse();

        verify(flow, times(1)).onMessage(any());
    }

    @Test
    void callbackQueriesGoToTheFlow() {
        when(processed.claim(8L)).thenReturn(1);
        handler.handle(json("{\"update_id\":8,\"callback_query\":{\"id\":\"q\",\"from\":{\"id\":42},\"data\":\"lang:ru\"}}"));
        verify(flow).onCallbackQuery(any());
    }

    @Test
    void aGroupMessageIsIgnored() {
        when(processed.claim(9L)).thenReturn(1);
        handler.handle(json("{\"update_id\":9,\"message\":{\"from\":{\"id\":42},\"chat\":{\"id\":-5,\"type\":\"group\"},\"text\":\"/start\"}}"));
        verify(flow, never()).onMessage(any());
    }

    @Test
    void anUpdateWithoutAnIdIsIgnored() {
        assertThat(handler.handle(json("{\"message\":{}}"))).isFalse();
        verify(processed, never()).claim(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void aFailureIsSwallowedAndTheUpdateStaysHandled() {
        when(processed.claim(11L)).thenReturn(1).thenReturn(0);
        doThrow(new IllegalStateException("boom")).when(flow).onMessage(any());

        assertThat(handler.handle(privateMessage(11))).isTrue();
        assertThat(handler.handle(privateMessage(11))).isFalse();
        verify(flow, times(1)).onMessage(any());
    }
}
