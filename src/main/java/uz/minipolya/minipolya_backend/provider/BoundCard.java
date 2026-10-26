package uz.minipolya.minipolya_backend.provider;

/**
 * A confirmed card binding. This is everything we are allowed to keep: a token
 * to charge later and a masked number to show the player.
 *
 * @param token        provider token used for every later charge
 * @param maskedNumber display form, e.g. {@code 8600 •••• 1234}
 * @param cardType     {@code uzcard}, {@code humo} or {@code unknown}
 */
public record BoundCard(String token, String maskedNumber, String cardType) {
}
