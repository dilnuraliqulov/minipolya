package uz.minipolya.minipolya_backend.provider;

/**
 * A confirmed binding: everything we may keep. The token is a secret — stored encrypted,
 * never logged, never returned to a client.
 *
 * @param cardToken  token for later charges and holds
 * @param cardId     the provider's id for the card, needed to remove it
 * @param maskedPan  display form, {@code 8600 •••• •••• 1234} ({@link CardMask#pan})
 * @param expiry     the expiry the provider reports, {@code MM/YY}
 * @param cardHolder the name on the card as the provider reports it, or null
 */
public record BoundCard(String cardToken, String cardId, String maskedPan, String expiry, String cardHolder) {

    public BoundCard(String cardToken, String cardId, String maskedPan, String expiry) {
        this(cardToken, cardId, maskedPan, expiry, null);
    }

    /** Never render the token. */
    @Override
    public String toString() {
        return "BoundCard(cardId=" + cardId + ", maskedPan=" + maskedPan + ", expiry=" + expiry + ")";
    }
}
