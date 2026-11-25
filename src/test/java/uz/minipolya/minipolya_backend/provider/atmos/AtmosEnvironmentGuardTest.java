package uz.minipolya.minipolya_backend.provider.atmos;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.minipolya.minipolya_backend.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ATMOS_ENVIRONMENT says which side of ATMOS a server is on, because nothing else can: the
 * sandbox answers on the same host with credentials that look the same.
 */
class AtmosEnvironmentGuardTest {

    private static AppProperties properties(String environment) {
        AppProperties properties = new AppProperties();
        properties.getAtmos().setEnvironment(environment);
        return properties;
    }

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    void anUnsetValueMeansTheSandbox() {
        // The safe end of the mistake: a sandbox takes no money from anyone.
        assertThat(AtmosEnvironment.of(null)).isEqualTo(AtmosEnvironment.SANDBOX);
        assertThat(AtmosEnvironment.of("")).isEqualTo(AtmosEnvironment.SANDBOX);
        assertThat(AtmosEnvironment.of("   ")).isEqualTo(AtmosEnvironment.SANDBOX);
    }

    @Test
    void spacesAndCapitalsAreForgivenAndTheValueIsWhatGoesOnTheWire() {
        assertThat(AtmosEnvironment.of(" Production ")).isEqualTo(AtmosEnvironment.PRODUCTION);
        assertThat(AtmosEnvironment.of("SANDBOX")).isEqualTo(AtmosEnvironment.SANDBOX);
        assertThat(AtmosEnvironment.PRODUCTION.value()).isEqualTo("production");
        assertThat(AtmosEnvironment.SANDBOX.value()).isEqualTo("sandbox");
    }

    /** Anything else is a mistake worth stopping for — and the value is never echoed back. */
    @Test
    void anythingElseRefusesToStartWithoutRepeatingTheValue() {
        for (String wrong : new String[] {"sandbok", "testing", "live", "AKfQ9wLb"}) {
            assertThatThrownBy(() -> AtmosEnvironment.of(wrong))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ATMOS_ENVIRONMENT")
                    .hasMessageContaining("sandbox")
                    .hasMessageContaining("production")
                    .hasMessageNotContaining(wrong);
        }
    }

    @Test
    void outsideProdAnyValidValueIsFine() {
        assertThat(AtmosEnvironmentGuard.check(null, false)).isEqualTo(AtmosEnvironment.SANDBOX);
        assertThat(AtmosEnvironmentGuard.check("sandbox", false)).isEqualTo(AtmosEnvironment.SANDBOX);
        // A staging server may be pointed at production on purpose; only prod is held to it.
        assertThat(AtmosEnvironmentGuard.check("production", false)).isEqualTo(AtmosEnvironment.PRODUCTION);
    }

    @Test
    void prodWithoutItRefusesToStartRatherThanAssumeTheSandbox() {
        assertThatThrownBy(() -> AtmosEnvironmentGuard.check(null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ATMOS_ENVIRONMENT=production")
                .hasMessageContaining("no money would ever move");
    }

    @Test
    void prodOnTheSandboxRefusesToStart() {
        assertThatThrownBy(() -> AtmosEnvironmentGuard.check("sandbox", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ATMOS_ENVIRONMENT=production")
                .hasMessageContaining("takes nobody's money");
    }

    @Test
    void prodOnProductionStarts() {
        assertThat(AtmosEnvironmentGuard.check("production", true)).isEqualTo(AtmosEnvironment.PRODUCTION);
    }

    @Test
    void theGuardReadsTheActiveProfilesAndTheConfiguredValue() {
        assertThatCode(() -> new AtmosEnvironmentGuard(profiles("staging", "atmos"), properties(null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new AtmosEnvironmentGuard(profiles("prod", "atmos"), properties("production")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new AtmosEnvironmentGuard(profiles("prod", "atmos"), properties(null)))
                .isInstanceOf(IllegalStateException.class);
        // A bad value is refused whatever the profile, including a developer's machine.
        assertThatThrownBy(() -> new AtmosEnvironmentGuard(profiles("dev"), properties("staging")))
                .isInstanceOf(IllegalStateException.class);
    }
}
