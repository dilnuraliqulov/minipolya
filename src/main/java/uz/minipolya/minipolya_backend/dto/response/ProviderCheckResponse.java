package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The answer to {@code GET /api/admin/payments/provider-check}: can the card provider be
 * reached right now, with the credentials this server has?
 *
 * <p>Everything here is safe to show an admin and nothing else is here: no access token, no
 * consumer key or secret, no request or response body, and the base URL's <b>host</b> rather
 * than the URL (which can carry user info).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProviderCheckResponse {

    public static final String OK = "ok";
    public static final String FAILED = "failed";

    /** {@value #OK} or {@value #FAILED}. */
    private String status;

    /** Which provider is wired in: {@code atmos} or {@code fake}. */
    private String provider;

    /** The host of the provider's base URL, e.g. {@code apigw.atmos.uz}; absent for {@code fake}. */
    private String host;

    /** The provider's own code for the failure, e.g. {@code HTTP_401}; absent when ok. */
    private String errorCode;
}
