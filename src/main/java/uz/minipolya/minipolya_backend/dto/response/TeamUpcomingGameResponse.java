package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The team's next game, for the "Keyingi oʻyin" card on its page: when and where it is,
 * and who it is against. {@code opponentName} is absent while the game is still waiting
 * for an opponent — the card says so rather than inventing one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeamUpcomingGameResponse {

    private UUID gameId;

    /** UTC, as every timestamp here. */
    private OffsetDateTime startsAt;

    /** The game's own status: {@code open} while an opponent is awaited, else {@code confirmed}. */
    private String status;

    private String stadiumName;

    /** The other team in the game; absent when nobody has joined yet. */
    private String opponentName;
}
