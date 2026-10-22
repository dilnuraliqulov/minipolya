package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinGameRequest {

    @NotNull
    private UUID gameId;

    /** Only for team_vs_team when Team B's leader joins: "a" or "b". */
    @Pattern(regexp = "a|b", message = "side must be 'a' or 'b'")
    private String side;

    /**
     * The accepting team, required when joining a team_vs_team game as side "b".
     * The caller must be that team's leader. Ignored for casual games and for
     * invited members, who join without a side and do not pay for the pitch.
     */
    private UUID teamId;
}
