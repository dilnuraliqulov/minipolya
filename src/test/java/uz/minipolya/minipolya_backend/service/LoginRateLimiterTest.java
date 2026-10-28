package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.exception.TooManyLoginAttemptsException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LoginRateLimiterTest {

    private final AdminAuthServiceTest.MutableClock clock =
            new AdminAuthServiceTest.MutableClock(Instant.parse("2026-10-28T09:00:00Z"));
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock, new AppProperties());

    @Test
    void twentyAttemptsPerFifteenMinutesAreAllowedAndTheTwentyFirstIsNot() {
        for (int i = 0; i < 20; i++) {
            limiter.acquire("203.0.113.7");
        }

        TooManyLoginAttemptsException ex = catchThrowableOfType(TooManyLoginAttemptsException.class,
                () -> limiter.acquire("203.0.113.7"));

        assertThat(ex.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(ex.getMinutesRemaining()).isEqualTo(15);
    }

    @Test
    void otherAddressesAreNotAffected() {
        for (int i = 0; i < 20; i++) {
            limiter.acquire("203.0.113.7");
        }

        assertThatCode(() -> limiter.acquire("198.51.100.1")).doesNotThrowAnyException();
    }

    @Test
    void theWindowSlides() {
        for (int i = 0; i < 20; i++) {
            limiter.acquire("203.0.113.7");
            clock.advance(Duration.ofSeconds(30));
        }
        // The first attempt was 10 minutes ago: 5 more minutes until it drops out.
        assertThat(catchThrowableOfType(TooManyLoginAttemptsException.class,
                () -> limiter.acquire("203.0.113.7")).getMinutesRemaining()).isEqualTo(5);

        clock.advance(Duration.ofMinutes(5));
        assertThatCode(() -> limiter.acquire("203.0.113.7")).doesNotThrowAnyException();
    }
}
