package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.minipolya.minipolya_backend.dto.response.ProviderCheckResponse;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.ProviderCheck;

/**
 * Which card provider this server talks to, and whether it answers right now — what the
 * admin panel's Sozlamalar page shows. The real client asks ATMOS for a fresh access token;
 * the fake provider has nothing to reach and always says so.
 *
 * <p>Touches no database and changes nothing, so there is no audit entry: a check is not an
 * action on data. A provider that refuses or cannot be reached is a normal answer with
 * {@code status: "failed"} and its code — "it is down" is what the admin asked.
 */
@Service
@RequiredArgsConstructor
public class ProviderCheckService {

    private final PaymentProvider paymentProvider;

    public ProviderCheckResponse check() {
        ProviderCheck check = paymentProvider.check();
        return ProviderCheckResponse.builder()
                .status(check.ok() ? ProviderCheckResponse.OK : ProviderCheckResponse.FAILED)
                .provider(check.provider())
                .host(check.host())
                .environment(check.environment())
                .errorCode(check.errorCode())
                .build();
    }
}
