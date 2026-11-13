package uz.minipolya.minipolya_backend.config.env;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application-staging.yaml} is read when the staging profile is active, and the
 * server settings it relies on resolve.
 *
 * <p>The guards that make staging what it is are tested on their own: that the fake payment
 * provider may run here ({@code FakeProviderGuardTest}), that the dev sign-in may not
 * ({@code DevProfileGuardTest}), that polling may ({@code TelegramBotModeGuardTest}), that
 * a real {@code CARD_TOKEN_KEY} is required ({@code CardTokenCipherTest}) and that a missing
 * setting stops startup ({@link RequiredEnvironmentPostProcessorTest}).
 */
class StagingProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Reads application.yaml and the profile's application-staging.yaml, as a real
            // start-up does; no bean is created, so no database is needed.
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void stagingForwardsTheProxyHeadersSoTheClientIpIsTheRealOne() {
        runner.withPropertyValues("spring.profiles.active=staging").run(context -> {
            // Without this, request.getRemoteAddr() is the platform's edge proxy: every admin
            // would share one per-IP sign-in budget and every audit entry would name the proxy.
            assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy"))
                    .isEqualTo("native");
        });
    }

    @Test
    void otherProfilesDoNotTrustForwardedHeaders() {
        // Only a server behind a known proxy may believe X-Forwarded-For.
        runner.run(context ->
                assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isNull());
        runner.withPropertyValues("spring.profiles.active=dev").run(context ->
                assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isNull());
    }

    @Test
    void thePortAndBindAddressFollowTheEnvironmentWithDevelopmentDefaults() {
        // PORT and SERVER_ADDRESS are unset here, so the development defaults apply.
        runner.withPropertyValues("spring.profiles.active=staging").run(context -> {
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080");
            assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("0.0.0.0");
        });
    }

    @Test
    void aHostAssignedPortAndAnIpv6BindAreTakenFromTheEnvironment() {
        runner.withPropertyValues("spring.profiles.active=staging", "PORT=7070", "SERVER_ADDRESS=::")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("7070");
                    assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("::");
                });
    }

    @Test
    void healthIsTheOnlyActuatorEndpointExposed() {
        runner.withPropertyValues("spring.profiles.active=staging").run(context ->
                assertThat(context.getEnvironment()
                        .getProperty("management.endpoints.web.exposure.include")).isEqualTo("health"));
    }
}
