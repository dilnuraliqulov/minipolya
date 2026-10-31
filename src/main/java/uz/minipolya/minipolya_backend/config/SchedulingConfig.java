package uz.minipolya.minipolya_backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the {@code @Scheduled} jobs on. Always on in the application; database-backed
 * tests switch it off ({@code app.scheduling.enabled=false}) so no job changes rows
 * while a test reads them.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
