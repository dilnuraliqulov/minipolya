package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.service.PromotionService;

/** Deactivates promotions whose window has ended. Runs hourly; idempotent. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromotionExpiryJob {

    private final PromotionService promotionService;

    @Scheduled(cron = "0 0 * * * *")
    public void expirePromotions() {
        promotionService.expireEndedPromotions();
    }
}
