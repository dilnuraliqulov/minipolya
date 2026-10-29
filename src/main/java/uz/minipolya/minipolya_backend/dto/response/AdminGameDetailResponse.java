package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One game for the admin panel, read-only: every {@link AdminGameResponse} field, plus
 * each team's money and players (team games) or the participants (casual games).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminGameDetailResponse {

    @JsonUnwrapped
    private AdminGameResponse game;

    /** team_vs_team only: the teams currently in the game. */
    private List<AdminGameTeamResponse> teams;

    /** Casual games only. */
    private List<AdminGamePlayerResponse> participants;

    /** Tiyin, casual games only: the platform's fee per player. */
    private Long serviceFeePerPerson;
}
