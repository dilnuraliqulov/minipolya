package uz.minipolya.minipolya_backend.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test against a real PostgreSQL ({@link TestDatabaseCondition}): the full Spring
 * context, Liquibase schema, security filter chain and MockMvc. Each test runs in a
 * transaction that is rolled back, so tests do not see each other's rows. The
 * scheduled jobs are off, and Hibernate statistics are on for query counting
 * ({@link QueryCounter}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TestDatabaseCondition.class)
@SpringBootTest(properties = {
        "spring.datasource.url=${TEST_DB_URL:" + TestDatabaseCondition.DEFAULT_URL + "}",
        "spring.datasource.username=${DB_USERNAME:postgres}",
        "spring.datasource.password=${DB_PASSWORD:}",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN",
        "app.scheduling.enabled=false",
        "app.payments.provider=fake",
        "app.jwt.secret=${JWT_SECRET:test-secret-test-secret-test-secret-0123456789}",
        "app.bot.token=${BOT_TOKEN:test-bot-token}"
})
@AutoConfigureMockMvc
@Transactional
public @interface DatabaseTest {
}
