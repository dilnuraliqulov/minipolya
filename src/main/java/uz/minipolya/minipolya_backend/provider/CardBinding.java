package uz.minipolya.minipolya_backend.provider;

/**
 * A card binding the provider has started but not yet confirmed. The card
 * number itself is never kept — the provider holds it against this id until the
 * SMS code confirms the binding.
 *
 * @param bindingId the provider's handle for this pending binding
 */
public record CardBinding(String bindingId) {
}
