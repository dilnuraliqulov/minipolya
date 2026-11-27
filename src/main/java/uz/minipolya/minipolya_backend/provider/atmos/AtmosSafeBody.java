package uz.minipolya.minipolya_backend.provider.atmos;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ATMOS response body as it may be written to the log: every field that could carry card
 * data, a token or a secret replaced by {@code "***"}, and the rest cut short.
 *
 * <p>Bodies were not logged at all until {@code POST /hold/multiple/create} started answering
 * HTTP 403 and the log said only "HTTP 403" — which does not say whether the gateway refused
 * the path, the request or the merchant account. The body is the only place that is written
 * down, so it is logged; the hard rule that card numbers, expiry dates, SMS codes and card
 * tokens are never logged is kept by redacting them <b>by field name before anything is
 * written</b>, rather than by trusting each caller to pass something safe.
 *
 * <p>The list is deliberately wider than it needs to be: {@code card_id} reads like an id but
 * holds the card <em>token</em> in a capture answer, and {@code token} covers both the card
 * token of {@code remove-card} and an access token. A field this does not know is printed as
 * it came, which is why nothing but an ATMOS result body is ever passed to it — and why it is
 * capped: a gateway error page is HTML, by the kilobyte.
 */
final class AtmosSafeBody {

    /** Enough for any ATMOS result body or gateway fault; an HTML page is cut off here. */
    static final int MAX = 800;

    private static final Pattern SECRETS = Pattern.compile(
            "\"(card_token|card_number|card_pan|pan|expiry|card_expiry|otp|token|access_token"
                    + "|refresh_token|card_id|card_holder|password|consumer_key|consumer_secret)\""
                    + "\\s*:\\s*(\"[^\"]*\"|-?[0-9]+(\\.[0-9]+)?|true|false|null)",
            Pattern.CASE_INSENSITIVE);

    /** A phone number is never logged in full, not even the masked one ATMOS sends back. */
    private static final Pattern PHONE = Pattern.compile(
            "\"(phone|phone_number)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private AtmosSafeBody() {
    }

    /**
     * @param body the raw response body, or null
     * @return the body with every known secret field redacted, on one line, at most
     *         {@link #MAX} characters, or {@code "<empty>"} when there was nothing
     */
    static String of(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String safe = SECRETS.matcher(body).replaceAll(AtmosSafeBody::redacted);
        safe = PHONE.matcher(safe).replaceAll(AtmosSafeBody::redacted);
        safe = WHITESPACE.matcher(safe).replaceAll(" ").trim();
        return safe.length() <= MAX ? safe : safe.substring(0, MAX) + "…(cut)";
    }

    private static String redacted(MatchResult match) {
        return "\"" + Matcher.quoteReplacement(match.group(1)) + "\":\"***\"";
    }
}
