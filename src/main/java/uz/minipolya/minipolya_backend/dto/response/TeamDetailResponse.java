package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.TeamLevel;

import java.util.List;
import java.util.UUID;

/** Full team view including the member list with names and roles. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeamDetailResponse {

    private UUID id;
    private String name;
    private UUID leaderId;
    private String leaderName;
    private List<TeamMemberResponse> members;

    /** {@code havaskor} / {@code orta} / {@code professional}; absent when the team has none yet. */
    private TeamLevel level;

    /** How many team games this team has played: confirmed games whose hour has started. */
    private Integer gamesPlayed;

    /** The soonest team game ahead of this team — waiting or confirmed; absent when there is none. */
    private TeamUpcomingGameResponse upcomingGame;
}
