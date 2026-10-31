package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The documented envelope for every endpoint: {@code { success, data, message }}.
 * All three keys are always present — {@code data} is null on an error and
 * {@code message} is null on a plain success — so clients can read them without
 * checking whether the field exists.
 *
 * <p>An error a client must tell apart programmatically (e.g. {@code INVALID_CREDENTIALS},
 * {@code ACCOUNT_LOCKED}) also carries a {@code code}. That key appears only when set,
 * so every other response keeps exactly the three keys above.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .message(message)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(message)
                .data(data)
                .build();
    }
}
