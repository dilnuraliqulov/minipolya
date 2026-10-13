package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Partial update of a user's flags by an admin. Null fields are left unchanged. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUpdateUserRequest {

    private Boolean isOwner;
    private Boolean isAdmin;
    /** Accepted but not yet persisted — the users table has no blocked flag. */
    private Boolean isBlocked;
}
