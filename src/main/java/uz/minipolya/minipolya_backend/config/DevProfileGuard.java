package uz.minipolya.minipolya_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * The {@code dev} profile turns on the dev-only sign-in (DevAuthController), which issues
 * a token for any user without Telegram. It must never run next to real money or real
 * users: with {@code prod} or {@code atmos} also active the application refuses to start.
 * It also refuses to start without a {@code DEV_LOGIN_KEY} of at least
 * {@value #MIN_LOGIN_KEY_LENGTH} characters, which every dev sign-in request must send
 * ({@link DevLoginKeyInterceptor}).
 */
@Configuration
@Profile("dev")
public class DevProfileGuard {

    public static final int MIN_LOGIN_KEY_LENGTH = 16;

    public DevProfileGuard(Environment environment, AppProperties properties) {
        refuseWithRealProfiles(environment);
        requireLoginKey(properties.getDev().getLoginKey());
    }

    static void refuseWithRealProfiles(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod | atmos"))) {
            throw new IllegalStateException(
                    "The dev profile (dev-only sign-in) must not be active together with prod or atmos");
        }
    }

    static void requireLoginKey(String loginKey) {
        if (loginKey == null || loginKey.length() < MIN_LOGIN_KEY_LENGTH) {
            throw new IllegalStateException("The dev profile needs DEV_LOGIN_KEY (at least "
                    + MIN_LOGIN_KEY_LENGTH + " characters) for the dev-only sign-in");
        }
    }
}
