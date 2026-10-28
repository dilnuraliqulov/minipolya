package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Too many wrong passwords: password sign-in is refused until the lock lifts. HTTP 423. */
public class AccountLockedException extends CodedApiException {

    private final long minutesRemaining;

    public AccountLockedException(long minutesRemaining) {
        super(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                "Hisob vaqtincha bloklandi. " + minutesRemaining + " daqiqadan so'ng qayta urinib ko'ring",
                Map.of("minutesRemaining", minutesRemaining));
        this.minutesRemaining = minutesRemaining;
    }

    public long getMinutesRemaining() {
        return minutesRemaining;
    }
}
