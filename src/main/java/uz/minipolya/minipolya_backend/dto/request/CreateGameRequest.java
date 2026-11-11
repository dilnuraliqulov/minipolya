package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Future;
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

    /** Optional: a game is exactly one hour. Absent or 1; anything else is 400. */
    private Integer duration;

    /** Only used for team_vs_team: the creating team. */
    private UUID teamAId;

    /**
     * The saved card to put the platform fee and owner advance on hold. Required
     * for team_vs_team, where creating the game is also paying for it; ignored
     * for casual games, which still pay per player afterwards.
     */
    private UUID cardId;

    /**
     * team_vs_team only (T6): {@code MINIMUM} (the default when absent) or {@code FULL} — how
     * much the creating team puts on hold. A minimum the stadium cannot offer is taken as full.
     */
    private PaymentOption paymentOption;
}
