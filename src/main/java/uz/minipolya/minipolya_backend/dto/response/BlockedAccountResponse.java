package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * The {@code data} of a blocked-account 403 ({@code code: ACCOUNT_BLOCKED}): why and when,
 * from the latest {@code USER_BLOCKED} audit entry. Each field is absent when unknown.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BlockedAccountResponse {

    /** The reason the admin typed, as typed; absent when none was given. */
    private String reason;
    /** UTC. */
    private OffsetDateTime blockedAt;
}
