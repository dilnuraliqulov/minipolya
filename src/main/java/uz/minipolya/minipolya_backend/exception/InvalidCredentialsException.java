package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Wrong username or wrong password — deliberately the same answer for both, so
 * a caller cannot learn which usernames exist.
 */
public class InvalidCredentialsException extends CodedApiException {

    public static final String MESSAGE = "Login yoki parol noto'g'ri";

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", MESSAGE, null);
    }
}
