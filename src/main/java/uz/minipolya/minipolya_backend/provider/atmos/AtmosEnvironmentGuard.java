package uz.minipolya.minipolya_backend.provider.atmos;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import uz.minipolya.minipolya_backend.config.AppProperties;

/**
 * {@code ATMOS_ENVIRONMENT} is read at startup rather than at the first payment, and
 * production is held to it: under the {@code prod} profile the application refuses to start
 * unless it says {@code production}.
 *
 * <p>Both halves of that matter. A production server left on the ATMOS sandbox would take
 * nobody's money while appearing to work, and the mistake would be found by a stadium owner
 * who was never paid. The other way round — a test server on production — takes real money
 * from real cards, which is why {@link AtmosEnvironment#SANDBOX} is what an empty value
 * means everywhere else.
 *
 * <p>Not tied to a profile on purpose: a value that is neither {@code sandbox} nor
 * {@code production} is a mistake worth stopping for on a developer's machine too, and it
 * would otherwise surface only when an admin opened the Sozlamalar page.
 */
@Configuration
public class AtmosEnvironmentGuard {

    public AtmosEnvironmentGuard(Environment springEnvironment, AppProperties properties) {
        check(properties.getAtmos().getEnvironment(), springEnvironment.acceptsProfiles(Profiles.of("prod")));
    }

    /** @return the environment in force, for a caller that wants it; throws when it is wrong */
    static AtmosEnvironment check(String configured, boolean prod) {
        AtmosEnvironment environment = AtmosEnvironment.of(configured);
        if (!prod) {
            return environment;
        }
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("The prod profile needs " + AtmosEnvironment.VARIABLE
                    + "=production. It is not set, and an unset value means the ATMOS sandbox, where "
                    + "every payment would appear to work and no money would ever move.");
        }
        if (environment != AtmosEnvironment.PRODUCTION) {
            throw new IllegalStateException("The prod profile needs " + AtmosEnvironment.VARIABLE
                    + "=production, and this one is set to the sandbox. A production server on the "
                    + "sandbox takes nobody's money; if this really is a test server, it should not "
                    + "be running the prod profile.");
        }
        return environment;
    }
}
