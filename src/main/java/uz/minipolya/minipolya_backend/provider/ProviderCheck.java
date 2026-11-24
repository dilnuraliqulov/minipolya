package uz.minipolya.minipolya_backend.provider;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Whether the card provider can be reached right now, as
 * {@link PaymentProvider#check()} answers it.
 *
 * <p>It carries only what an admin may be shown: which provider is wired in
 * ({@value #ATMOS} or {@value #FAKE}), the <b>host</b> of its base URL and, when the check
 * failed, the provider's own error code. Deliberately nothing else — never an access token,
 * never a consumer key or secret, never a request or response body, and never the base URL
 * in full (a URL can carry user info; {@link #hostOf} keeps the host alone).
 *
 * @param provider which provider answered: {@value #ATMOS} or {@value #FAKE}
 * @param ok       true when the provider answered as expected
 * @param host     the host of the provider's base URL, null when it makes no calls (the fake
 *                 provider) or the URL has no host
 * @param errorCode the provider's code for the failure, null when {@code ok}
 */
public record ProviderCheck(String provider, boolean ok, String host, String errorCode) {

    /** The real card provider. */
    public static final String ATMOS = "atmos";
    /** The stand-in used in dev, in the tests and on a plain staging server. */
    public static final String FAKE = "fake";
    /** No answer in time: the provider is there or it is not, we did not find out. */
    public static final String TIMEOUT = "TIMEOUT";
    /** The call never got as far as an answer we could read a code from. */
    public static final String UNREACHABLE = "UNREACHABLE";

    public static ProviderCheck ok(String provider, String host) {
        return new ProviderCheck(provider, true, host, null);
    }

    public static ProviderCheck failed(String provider, String host, String errorCode) {
        return new ProviderCheck(provider, false, host, errorCode);
    }

    /**
     * The host of a base URL — {@code https://apigw.atmos.uz} is {@code apigw.atmos.uz} —
     * or null when it is empty or not a URL with one. Only the host: a URL may carry
     * {@code user:password@} before it, and that must never reach an API response or a log.
     */
    public static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            return new URI(baseUrl.trim()).getHost();
        } catch (URISyntaxException ex) {
            return null;
        }
    }
}
