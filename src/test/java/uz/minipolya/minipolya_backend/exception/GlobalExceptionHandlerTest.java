package uz.minipolya.minipolya_backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void malformedJsonBodyIsReportedAs400NotAs500() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("Unexpected character ('n' (code 110))", null, null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableBody(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
    }

    @Test
    void malformedJsonResponseDoesNotLeakTheParserMessage() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("com.fasterxml.jackson internals", null, null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableBody(ex);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).doesNotContain("jackson");
    }

    @Test
    void codedErrorsCarryTheirStatusCodeAndData() {
        ResponseEntity<ApiResponse<Object>> locked = handler.handleCoded(new AccountLockedException(12));

        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(locked.getBody()).isNotNull();
        assertThat(locked.getBody().isSuccess()).isFalse();
        assertThat(locked.getBody().getCode()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(locked.getBody().getData()).isEqualTo(Map.of("minutesRemaining", 12L));
    }

    @Test
    void invalidCredentialsIs401WithTheUzbekMessage() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleCoded(new InvalidCredentialsException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(response.getBody().getMessage()).isEqualTo("Login yoki parol noto'g'ri");
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void blockedAccountIs403() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleCoded(new AccountBlockedException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("ACCOUNT_BLOCKED");
    }
}
