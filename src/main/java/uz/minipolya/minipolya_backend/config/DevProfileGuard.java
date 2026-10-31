package uz.minipolya.minipolya_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * The {@code dev} profile turns on the dev-only sign-in (DevAuthController), which issues
 * a token for any user without Telegram. It must never run next to real money or real
 * users: with {@code prod} or {@code atmos} also active the application refuses to start.
 */
@Configuration
@Profile("dev")
public class DevProfileGuard {

    public DevProfileGuard(Environment environment) {
        refuseWithRealProfiles(environment);
    }

    static void refuseWithRealProfiles(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod | atmos"))) {
            throw new IllegalStateException(
                    "The dev profile (dev-only sign-in) must not be active together with prod or atmos");
        }
    }
}
