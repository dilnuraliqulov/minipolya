package uz.minipolya.minipolya_backend.provider.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The fake provider's storage; only while {@code app.payments.provider=fake}, never under {@code atmos}. */
@Configuration
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "fake")
@Profile("!atmos")
public class FakeProviderConfig {

    @Bean
    public FakeProviderStore fakeProviderStore() {
        return new InMemoryFakeProviderStore();
    }
}
