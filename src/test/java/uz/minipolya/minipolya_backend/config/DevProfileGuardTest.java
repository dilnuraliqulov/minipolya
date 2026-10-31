package uz.minipolya.minipolya_backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R19: the dev sign-in can never run next to real money or real users. */
class DevProfileGuardTest {

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    void devWithProdOrAtmosRefusesToStart() {
        for (String[] active : new String[][] {{"dev", "prod"}, {"dev", "atmos"}, {"prod", "atmos", "dev"}}) {
            assertThatThrownBy(() -> new DevProfileGuard(profiles(active)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not be active together with prod or atmos");
        }
    }

    @Test
    void devAloneStarts() {
        assertThatCode(() -> new DevProfileGuard(profiles("dev"))).doesNotThrowAnyException();
    }
}
