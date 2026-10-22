package uz.minipolya.minipolya_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the clock the application reads "now" from, so time-dependent logic
 * can be driven by a fixed clock in tests. Services convert to
 * {@code Asia/Tashkent} themselves where a local date or time is needed.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
