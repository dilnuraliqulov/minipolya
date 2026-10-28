package uz.minipolya.minipolya_backend.provider;

/**
 * A confirmed binding: everything we may keep. The token is a secret — stored encrypted,
 * never logged, never returned to a client.
 *
 * @param cardToken token for later charges and holds
 * @param cardId    the provider's id for the card, needed to remove it
 * @param maskedPan display form, e.g. {@code 8600 •••• 1234}
 * @param expiry    the expiry the provider reports, {@code MM/YY}
 */
public record BoundCard(String cardToken, String cardId, String maskedPan, String expiry) {

    /** Never render the token. */
    @Override
    public String toString() {
        return "BoundCard(cardId=" + cardId + ", maskedPan=" + maskedPan + ", expiry=" + expiry + ")";
    }
}
