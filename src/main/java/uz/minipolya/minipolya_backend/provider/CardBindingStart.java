package uz.minipolya.minipolya_backend.provider;

/**
 * A card binding the provider has started; the cardholder gets an SMS code. The card
 * number itself is never kept.
 *
 * @param bindingId   the provider's handle, confirmed with the code
 * @param maskedPhone where the code went, e.g. {@code ********9999}
 */
public record CardBindingStart(String bindingId, String maskedPhone) {
}
