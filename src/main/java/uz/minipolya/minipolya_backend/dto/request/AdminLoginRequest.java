package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin web panel sign-in. The password is checked against a BCrypt hash and never
 * stored or logged, so {@code toString} is suppressed to keep it out of log lines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminLoginRequest {

    @NotBlank(message = "username is required")
    @Size(max = 64, message = "username must be at most 64 characters")
    private String username;

    @NotBlank(message = "password is required")
    @Size(max = 200, message = "password must be at most 200 characters")
    private String password;

    /** Never render the password. */
    @Override
    public String toString() {
        return "AdminLoginRequest(username=" + username + ", password=****)";
    }
}
