package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Adding a card failed in a way the player can act on: HTTP 400 with a {@code code} the
 * Mini App can switch on, and a message it can show as is.
 *
 * <table>
 *   <tr><th>code</th><th>when</th></tr>
 *   <tr><td>CARD_NOT_SUPPORTED</td><td>not an Uzcard / Humo number</td></tr>
 *   <tr><td>CARD_EXPIRY_INVALID</td><td>the expiry does not match the card</td></tr>
 *   <tr><td>CARD_EXPIRED</td><td>the card has expired</td></tr>
 *   <tr><td>SMS_CODE_WRONG</td><td>wrong SMS code (the binding stays open)</td></tr>
 *   <tr><td>SMS_CODE_EXPIRED</td><td>the code is no longer valid: resend it</td></tr>
 *   <tr><td>SMS_RESEND_TOO_EARLY</td><td>a new code was asked for too soon</td></tr>
 *   <tr><td>SMS_RESEND_UNAVAILABLE</td><td>the provider cannot resend: enter the card again</td></tr>
 *   <tr><td>BINDING_NOT_FOUND</td><td>unknown or finished binding: start again</td></tr>
 *   <tr><td>CARD_BINDING_FAILED</td><td>any other refusal</td></tr>
 * </table>
 */
public class CardBindingException extends CodedApiException {

    public CardBindingException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message, null);
    }
}
