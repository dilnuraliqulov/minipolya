package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * T7: what a team may pay for one side of a team game, in player amounts (tiyin) only —
 * {@code GET /api/stadiums/{id}/team-game-quote} (creating) and
 * {@code GET /api/games/{id}/join-quote} (joining). {@code minimum} is listed only when the
 * stadium offers it; {@code full} always, last.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamGameQuoteResponse {

    private List<Option> options;

    /** The last moment the team could cancel and still get its money back. */
    private OffsetDateTime cancellationDeadline;

    /** One choice: {@code option} ("minimum" / "full"), what is paid now, and what is left after it. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Option {
        private String option;
        private long amount;
        private long remainderAmount;
    }
}
