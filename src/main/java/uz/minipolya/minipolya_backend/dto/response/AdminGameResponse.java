package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A game in the admin list. {@code teamAName} / {@code teamBName} name the teams
 * currently in a team game (absent while one side is still waiting for an opponent);
 * {@code currentPlayers} / {@code maxPlayers} are for casual games only. The
 * cancellation fields come from the audit entry that cancelled the game.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminGameResponse {

    private UUID id;
    private String type;
    private String status;
    private UUID stadiumId;
    private String stadiumName;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    /** Tiyin: the pitch's net price. */
    private Long totalPrice;
    private UUID creatorId;
    private String creatorName;
    private String teamAName;
    private String teamBName;
    private Integer currentPlayers;
    private Integer maxPlayers;
    private UUID bookingId;
    private OffsetDateTime cancelledAt;
    private String cancellationReason;
    private OffsetDateTime createdAt;
}
