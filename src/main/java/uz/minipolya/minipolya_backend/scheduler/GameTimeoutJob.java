package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.service.GameService;

/**
 * Cancels games that never filled. Every minute it finds {@code open} games
 * whose start time has passed, marks them {@code cancelled_not_filled} and
 * refunds any paid participants. Idempotent: a cancelled game no longer matches.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameTimeoutJob {

    private final GameService gameService;

    @Scheduled(fixedRate = 60_000)
    public void cancelUnfilledGames() {
        gameService.cancelUnfilledGames();
    }
}
