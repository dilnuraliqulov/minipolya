package uz.minipolya.minipolya_backend.dto.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLAUDE.md's "API Response Format" shows all three keys on every response,
 * with an explicit null for the one that does not apply.
 */
class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successCarriesAnExplicitNullMessage() {
        String json = objectMapper.writeValueAsString(ApiResponse.ok("payload"));

        assertThat(json).isEqualTo("{\"success\":true,\"data\":\"payload\",\"message\":null}");
    }

    @Test
    void errorCarriesAnExplicitNullData() {
        String json = objectMapper.writeValueAsString(ApiResponse.error("Slot is already booked"));

        assertThat(json)
                .isEqualTo("{\"success\":false,\"data\":null,\"message\":\"Slot is already booked\"}");
    }

    @Test
    void successWithAMessageCarriesBoth() {
        String json = objectMapper.writeValueAsString(ApiResponse.ok("payload", "Booking held for payment"));

        assertThat(json).isEqualTo(
                "{\"success\":true,\"data\":\"payload\",\"message\":\"Booking held for payment\"}");
    }

    @Test
    void aVoidPayloadStillReportsDataAsNull() {
        String json = objectMapper.writeValueAsString(ApiResponse.ok(null, "Member removed"));

        assertThat(json).isEqualTo("{\"success\":true,\"data\":null,\"message\":\"Member removed\"}");
    }
}
