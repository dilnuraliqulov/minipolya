package uz.minipolya.minipolya_backend.provider;

import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.util.UUID;

/** Mock Click integration. */
@Component
public class ClickProvider implements LegacyCheckoutProvider {

    @Override
    public Payment.Provider provider() {
        return Payment.Provider.CLICK;
    }

    @Override
    public String createCheckoutUrl(UUID paymentId) {
        // TODO: Replace with real Click API integration (create invoice, return pay URL).
        return "https://my.click.uz/mock/" + paymentId;
    }
}
