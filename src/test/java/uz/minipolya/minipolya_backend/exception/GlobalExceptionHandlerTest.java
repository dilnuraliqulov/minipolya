package uz.minipolya.minipolya_backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;

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
}
