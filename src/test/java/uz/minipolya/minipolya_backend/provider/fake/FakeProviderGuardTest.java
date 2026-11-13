package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.provider.FakePaymentProvider;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The fake provider can never take real payments' place: under {@code prod} the
 * application refuses to start with it, and under {@code atmos} (or any provider value
 * but {@code fake}) it is not even created — nor its store or dev view.
 */
class FakeProviderGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(AppProperties.class, AppProperties::new)
            .withBean(FakeProviderStore.class, InMemoryFakeProviderStore::new)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withUserConfiguration(FakePaymentProvider.class, FakePaymentsView.class);

    private ApplicationContextRunner withProfiles(String... profiles) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    @Test
    void itStartsInDev() {
        withProfiles("dev").withPropertyValues("app.payments.provider=fake")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FakePaymentProvider.class);
                    assertThat(context).hasSingleBean(FakePaymentsView.class);
                });
    }

    @Test
    void theApplicationRefusesToStartWithItUnderProd() {
        withProfiles("prod").withPropertyValues("app.payments.provider=fake")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("must never run under the prod or atmos profile"));
    }

    /** The staging test deployment runs on it on purpose: no provider contract, no real money. */
    @Test
    void itStartsUnderStaging() {
        withProfiles("staging").withPropertyValues("app.payments.provider=fake")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FakePaymentProvider.class);
                });
    }

    @Test
    void underAtmosItIsNeverCreated() {
        withProfiles("dev", "atmos").withPropertyValues("app.payments.provider=fake")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FakePaymentProvider.class);
                    assertThat(context).doesNotHaveBean(FakePaymentsView.class);
                });
    }

    @Test
    void anyOtherProviderValueLeavesItOut() {
        withProfiles("dev").withPropertyValues("app.payments.provider=atmos")
                .run(context -> assertThat(context).doesNotHaveBean(FakePaymentProvider.class));
    }
}
