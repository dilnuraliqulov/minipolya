package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** R32: what an invite link leads to, and whether the caller is already in that team. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamInvitePreviewResponse {

    private TeamResponse team;

    @JsonProperty("isMember")
    private boolean member;
}
