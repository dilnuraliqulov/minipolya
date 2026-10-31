package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

/** The account was blocked by an admin. Same message the Telegram sign-in and JwtAuthFilter use. */
public class AccountBlockedException extends CodedApiException {

    public AccountBlockedException() {
        this(null);
    }

    /** @param details why and when (a {@code BlockedAccountResponse}), sent as {@code data}; null for none. */
    public AccountBlockedException(Object details) {
        super(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", "Account is blocked", details);
    }
}
