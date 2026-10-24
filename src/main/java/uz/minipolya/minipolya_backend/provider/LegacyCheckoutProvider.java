package uz.minipolya.minipolya_backend.provider;

import uz.minipolya.minipolya_backend.entity.Payment;

import java.util.UUID;

/**
 * The old hosted-checkout integration (Payme, Click): it returns a URL the
 * frontend redirects the player to, and the provider later calls our webhook.
 *
 * <p><strong>Legacy — do not use in new flows.</strong> It survives only so the
 * existing game payment path keeps compiling until the team-vs-team work
 * replaces it. Everything new goes through {@link PaymentProvider}, which binds
 * a card once and then charges, holds, captures, releases and refunds it
 * directly.
 */
public interface LegacyCheckoutProvider {

    Payment.Provider provider();

    String createCheckoutUrl(UUID paymentId);
}
