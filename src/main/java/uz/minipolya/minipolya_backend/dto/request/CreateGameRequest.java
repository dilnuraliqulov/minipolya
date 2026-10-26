package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Future;
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
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateGameRequest {

    @NotNull
    private UUID stadiumId;

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
    @Min(value = 1, message = "duration must be at least 1 hour")
    private Integer duration;

    /** Only used for team_vs_team: the creating team. */
    private UUID teamAId;

    /**
     * The saved card to put the platform fee and owner advance on hold. Required
     * for team_vs_team, where creating the game is also paying for it; ignored
     * for casual games, which still pay per player afterwards.
     */
    private UUID cardId;
}
