package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.repository.TelegramProcessedUpdateRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Forgets handled Telegram update ids older than a week; Telegram never redelivers an update
 * after 24 hours. Daily at 04:10; idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramUpdateCleanupJob {

    static final int KEEP_DAYS = 7;

    private final TelegramProcessedUpdateRepository processedUpdates;

    @Scheduled(cron = "0 10 4 * * *")
    public void forgetOldUpdates() {
        int deleted = processedUpdates.deleteReceivedBefore(OffsetDateTime.now(ZoneOffset.UTC).minusDays(KEEP_DAYS));
        if (deleted > 0) {
            log.info("[telegram] forgot {} handled update ids", deleted);
        }
    }
}
