package uz.minipolya.minipolya_backend.provider.atmos;

import java.util.Locale;

/**
 * Which side of ATMOS a set of credentials belongs to: its {@code sandbox} or
 * {@code production}.
 *
 * <p>It cannot be worked out from anything else. The sandbox answers on the <b>same host</b>
 * as production ({@code apigw.atmos.uz}) and the credentials look alike, so a server would
 * otherwise have no way to say which one it is talking to — and the two mistakes are not
 * small ones: a production server on the sandbox takes nobody's money, and a test server on
 * production takes real money from real cards. So it is configured, in
 * {@code ATMOS_ENVIRONMENT}, and shown to an admin beside the provider.
 *
 * <p>Empty means {@link #SANDBOX}: that is the safe end of the mistake, and the default on
 * the test server. {@code AtmosEnvironmentGuard} refuses to start the {@code prod} profile
 * on anything but {@link #PRODUCTION}.
 */
public enum AtmosEnvironment {

    SANDBOX,
    PRODUCTION;

    /** The environment variable this comes from. */
    public static final String VARIABLE = "ATMOS_ENVIRONMENT";

    /** The value as it is written in the environment and sent to the admin panel. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The configured value, {@link #SANDBOX} when it is not set. Spaces and capitals are
     * forgiven ({@code " Production "} is production); anything else is refused.
     *
     * <p>The message never repeats the value: a mistake here is usually a mistyped variable
     * name in the deployment, and the thing in it could be any other value from that file.
     *
     * @throws IllegalStateException when the value is neither {@code sandbox} nor
     *                               {@code production}
     */
    public static AtmosEnvironment of(String configured) {
        if (configured == null || configured.isBlank()) {
            return SANDBOX;
        }
        String value = configured.trim().toLowerCase(Locale.ROOT);
        for (AtmosEnvironment environment : values()) {
            if (environment.value().equals(value)) {
                return environment;
            }
        }
        throw new IllegalStateException(VARIABLE + " must be exactly \"sandbox\" or \"production\" "
                + "(it is neither, and is not repeated here in case the wrong variable was pasted "
                + "into it). Leave it empty to mean sandbox.");
    }
}
