package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An admin changing their own password. Neither password is ever stored or logged,
 * so {@code toString} is suppressed. BCrypt reads at most 72 bytes, which
 * AdminAuthService checks on top of the length here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "currentPassword is required")
    private String currentPassword;

    @NotBlank(message = "newPassword is required")
    @Size(min = 10, message = "newPassword must be at least 10 characters")
    private String newPassword;

    /** Never render either password. */
    @Override
    public String toString() {
        return "ChangePasswordRequest(currentPassword=****, newPassword=****)";
    }
}
