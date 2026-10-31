package uz.minipolya.minipolya_backend.service;

import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.exception.TooManyLoginAttemptsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP sliding-window limit on admin sign-in attempts, right or wrong. Kept in
 * memory, which is enough for a single server: a restart forgets the counts, and
 * a second instance would keep its own.
 *
 * <p>This is a second line of defence next to the per-account lockout: it slows a
 * caller who sprays many usernames from one address, which the per-account counter
 * cannot see.
 */
@Component
public class LoginRateLimiter {

    /** Past this many tracked addresses, stale ones are swept on the next attempt. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Clock clock;
    private final int maxAttempts;
    private final Duration window;
    private final Map<String, Deque<Instant>> attemptsByIp = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock, AppProperties appProperties) {
        this.clock = clock;
        this.maxAttempts = appProperties.getAdminLogin().getMaxAttemptsPerIp();
        this.window = Duration.ofMinutes(appProperties.getAdminLogin().getIpWindowMinutes());
    }

    /**
     * Records one attempt from {@code ip}, or throws when that address has used up
     * its attempts for the current window (the rejected attempt is not counted).
     */
    public void acquire(String ip) {
        Instant now = clock.instant();
        if (attemptsByIp.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
        Deque<Instant> attempts = attemptsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (attempts) {
            dropExpired(attempts, now);
            if (attempts.size() >= maxAttempts) {
                Duration untilFree = Duration.between(now, attempts.peekFirst().plus(window));
                throw new TooManyLoginAttemptsException(ceilMinutes(untilFree));
            }
            attempts.addLast(now);
        }
    }

    private void dropExpired(Deque<Instant> attempts, Instant now) {
        Instant cutoff = now.minus(window);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
            attempts.pollFirst();
        }
    }

    private void sweep(Instant now) {
        attemptsByIp.values().removeIf(attempts -> {
            synchronized (attempts) {
                dropExpired(attempts, now);
                return attempts.isEmpty();
            }
        });
    }

    static long ceilMinutes(Duration duration) {
        long seconds = Math.max(duration.toSeconds(), 0);
        return Math.max(1, (seconds + 59) / 60);
    }
}
