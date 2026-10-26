package uz.minipolya.minipolya_backend.provider;

import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.util.UUID;

/** Mock Payme integration. */
@Component
public class PaymeProvider implements LegacyCheckoutProvider {

    @Override
    public Payment.Provider provider() {
        return Payment.Provider.PAYME;
    }

    @Override
    public String createCheckoutUrl(UUID paymentId) {
        // TODO: Replace with real Payme API integration (create receipt, return pay URL).
        return "https://checkout.paycom.uz/mock/" + paymentId;
    }
}
