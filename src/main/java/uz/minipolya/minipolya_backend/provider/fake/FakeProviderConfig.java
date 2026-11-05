package uz.minipolya.minipolya_backend.provider.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The fake provider's storage, in the database so it survives a restart. Only while
 * {@code app.payments.provider=fake}, never under {@code atmos}: under the real provider
 * the {@code fake_payment_*} tables exist but are never touched.
 */
@Configuration
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "fake")
@Profile("!atmos")
public class FakeProviderConfig {

    @Bean
    public FakeProviderStore fakeProviderStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcFakeProviderStore(jdbcTemplate, transactionManager);
    }
}
