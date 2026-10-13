package uz.minipolya.minipolya_backend.provider;

import uz.minipolya.minipolya_backend.entity.Payment;

/**
 * A checkout provider (Payme, Click). Implementations create a hosted checkout
 * URL the frontend redirects the player to; the provider later calls our
 * webhook to confirm the payment.
 */
public interface PaymentProvider {

    Payment.Provider provider();

    String createCheckoutUrl(Long paymentId);
}
