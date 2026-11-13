package uz.minipolya.minipolya_backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R19: the dev sign-in can never run next to real money or real users, nor without its key. */
class DevProfileGuardTest {

    private static final String GOOD_KEY = "0123456789abcdef"; // exactly the minimum, 16

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    private static AppProperties withKey(String key) {
        AppProperties properties = new AppProperties();
        properties.getDev().setLoginKey(key);
        return properties;
    }

    @Test
    void devOnADeployedServerRefusesToStart() {
        for (String[] active : new String[][] {{"dev", "prod"}, {"dev", "atmos"}, {"prod", "atmos", "dev"},
                {"dev", "staging"}, {"staging", "dev"}}) {
            assertThatThrownBy(() -> new DevProfileGuard(profiles(active), withKey(GOOD_KEY)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not be active together with prod, staging or atmos");
        }
    }

    @Test
    void devWithAKeyOfAtLeast16CharactersStarts() {
        assertThatCode(() -> new DevProfileGuard(profiles("dev"), withKey(GOOD_KEY))).doesNotThrowAnyException();
        assertThatCode(() -> new DevProfileGuard(profiles("dev"), withKey(GOOD_KEY + "-longer")))
                .doesNotThrowAnyException();
    }

    @Test
    void devWithAMissingOrShortKeyRefusesToStart() {
        for (String key : new String[] {null, "", "0123456789abcde"}) { // the last is 15 characters
            assertThatThrownBy(() -> new DevProfileGuard(profiles("dev"), withKey(key)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DEV_LOGIN_KEY (at least 16 characters)");
        }
    }

    @Test
    void theInterceptorRefusesAMissingKeyWithTheSameMessage() {
        assertThatThrownBy(() -> new DevLoginKeyInterceptor(withKey(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV_LOGIN_KEY (at least 16 characters)");
    }

    @Test
    void theKeyMustMatchExactly() {
        DevLoginKeyInterceptor interceptor = new DevLoginKeyInterceptor(withKey(GOOD_KEY));
        assertThat(interceptor.matches(GOOD_KEY)).isTrue();
        assertThat(interceptor.matches(null)).isFalse();
        assertThat(interceptor.matches("")).isFalse();
        assertThat(interceptor.matches(GOOD_KEY + "x")).isFalse();
        assertThat(interceptor.matches("0123456789ABCDEF")).isFalse();
    }
}
