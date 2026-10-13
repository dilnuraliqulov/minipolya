package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One participant row in a {@link GameDetailResponse}. Placeholder shape until
 * the game_participants table and its service land in step 10 — fields may be
 * refined there.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameParticipantResponse {

    private Long userId;
    private String userName;
    /** Assigned side for open / team_vs_team games: "a" or "b". */
    private String side;

    @JsonProperty("hasPaid")
    private boolean hasPaid;
}
