package uz.minipolya.minipolya_backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * An error that carries its own HTTP status and a machine-readable {@code code},
 * rendered by {@link GlobalExceptionHandler} as {@code { success:false, code, message, data }}.
 */
@Getter
public abstract class CodedApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    /** Optional extra detail for the client (e.g. minutes until a lock lifts); null when none. */
    private final Object data;

    protected CodedApiException(HttpStatus status, String code, String message, Object data) {
        super(message);
        this.status = status;
        this.code = code;
        this.data = data;
    }
}
