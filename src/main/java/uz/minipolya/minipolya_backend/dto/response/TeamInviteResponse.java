package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** R32: a team's invite code — the leader builds the link from it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamInviteResponse {

    private String code;
}
