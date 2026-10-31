package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** One team of a team game for the admin panel: its {@link GameTeamResponse} money, plus its players. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminGameTeamResponse {

    @JsonUnwrapped
    private GameTeamResponse team;

    private List<AdminGamePlayerResponse> players;
}
