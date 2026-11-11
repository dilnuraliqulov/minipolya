package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/auth/dev/login} ({@code dev} profile only). The id is a string so a
 * malformed one is the usual 400 "Invalid id" rather than "Malformed request body".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevLoginRequest {

    private String userId;
}
