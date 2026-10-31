package uz.minipolya.minipolya_backend.telegram;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Polling mode, for running the bot locally without a public URL: deletes the webhook, then
 * loops on getUpdates and hands each update to {@link TelegramUpdateHandler}, the same handler
 * the webhook uses. Exists only under the {@code dev} profile with TELEGRAM_BOT_MODE=polling;
 * {@link TelegramBotModeGuard} refuses polling anywhere else.
 */
@Slf4j
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.bot.mode", havingValue = "polling")
@RequiredArgsConstructor
public class TelegramPolling {

    static final int POLL_TIMEOUT_SECONDS = 25;
    private static final long RETRY_PAUSE_MS = 5_000;

    private final TelegramApi api;
    private final TelegramUpdateHandler handler;

    private volatile boolean running;
    private Thread worker;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!api.isActive()) {
            log.warn("[telegram] polling mode, but the bot is not active (no BOT_TOKEN?): not polling");
            return;
        }
        running = true;
        worker = Thread.ofPlatform().daemon().name("telegram-polling").start(this::loop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        try {
            api.call("deleteWebhook", Map.of("drop_pending_updates", false));
            log.info("[telegram] polling started (webhook deleted)");
        } catch (TelegramApiException ex) {
            log.warn("[telegram] deleteWebhook: {}", ex.getMessage());
        }
        long offset = 0;
        while (running) {
            try {
                JsonNode updates = api.call("getUpdates", Map.of(
                        "offset", offset,
                        "timeout", POLL_TIMEOUT_SECONDS,
                        "allowed_updates", List.of("message", "callback_query")));
                for (JsonNode update : updates) {
                    offset = Math.max(offset, update.path("update_id").asLong() + 1);
                    handler.handle(update);
                }
            } catch (TelegramApiException ex) {
                if (!running) {
                    return;
                }
                // 409: a webhook was set again, or another process polls with the same token.
                log.warn("[telegram] getUpdates: {}", ex.getMessage());
                pause();
            } catch (RuntimeException ex) {
                log.error("[telegram] polling loop: {}", ex.getClass().getName());
                pause();
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
