package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

/** The account was blocked by an admin. Same message the Telegram sign-in and JwtAuthFilter use. */
public class AccountBlockedException extends CodedApiException {

    public AccountBlockedException() {
        super(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", "Account is blocked", null);
    }
}
