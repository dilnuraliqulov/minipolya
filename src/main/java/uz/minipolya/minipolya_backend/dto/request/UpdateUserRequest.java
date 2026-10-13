package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial profile update for {@code PATCH /api/users/me}. Both fields are
 * optional — only non-null values are applied.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {

    @Size(min = 1, max = 120, message = "name must be between 1 and 120 characters")
    private String name;

    @Pattern(regexp = "uz|ru|en", message = "language must be one of: uz, ru, en")
    private String language;
}
