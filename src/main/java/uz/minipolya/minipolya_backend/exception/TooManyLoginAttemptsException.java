package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Too many sign-in attempts from one IP address. HTTP 429. */
public class TooManyLoginAttemptsException extends CodedApiException {

    private final long minutesRemaining;

    public TooManyLoginAttemptsException(long minutesRemaining) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Juda ko'p urinish. " + minutesRemaining + " daqiqadan so'ng qayta urinib ko'ring",
                Map.of("minutesRemaining", minutesRemaining));
        this.minutesRemaining = minutesRemaining;
    }

    public long getMinutesRemaining() {
        return minutesRemaining;
    }
}
