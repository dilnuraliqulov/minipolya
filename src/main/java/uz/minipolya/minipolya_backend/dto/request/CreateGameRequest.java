package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateGameRequest {

    @NotNull
    private Long stadiumId;

    @NotNull
    @Pattern(regexp = "casual|open|team_vs_team", message = "type must be casual, open or team_vs_team")
    private String type;

    @Size(max = 200, message = "title must be at most 200 characters")
    private String title;

    @NotNull
    @Positive
    private Integer maxPlayers;

    @NotNull
    @Future
    private OffsetDateTime startsAt;

    @NotNull
    @Min(value = 1, message = "duration must be 1 or 2 hours")
    @Max(value = 2, message = "duration must be 1 or 2 hours")
    private Integer duration;

    /** Only used for team_vs_team: the creating team. */
    private Long teamAId;
}
