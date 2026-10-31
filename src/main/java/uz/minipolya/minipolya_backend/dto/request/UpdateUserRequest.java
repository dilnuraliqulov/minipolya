package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial profile update for {@code PATCH /api/users/me}. Every field is optional;
 * only non-null values are applied. {@code firstName}, {@code lastName} and
 * {@code phone} are checked by {@code UserService} (trimmed, with exact messages).
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

    /** Trimmed, 1-60 characters. Setting it (or lastName) rewrites name as "first last". */
    private String firstName;

    /** Trimmed, 1-60 characters. */
    private String lastName;

    /** {@code +998XXXXXXXXX}; must not belong to another account. */
    private String phone;
}
