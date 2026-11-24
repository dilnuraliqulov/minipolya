package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.GameMessage;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.TeamLevel;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One chat message as returned to clients. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameMessageResponse {

    private UUID id;
    private UUID gameId;

    /** Who sent it; absent on a system line, which nobody sent. */
    private UUID userId;
    private String userName;

    private String message;
    private OffsetDateTime createdAt;

    /** {@code user} or {@code system} (changeset 055). */
    private GameMessage.Kind kind;

    /** Which event a system line marks: {@code game_confirmed} or {@code team_joined}. */
    private String event;

    /** The team a system line is about, when it is about one. */
    private String eventTeamName;

    /** Which side the sender plays on; absent for a system line or a sender in neither team. */
    private GameParticipant.Side side;

    /** The sender's team in this game, so the chat can say who is speaking for whom. */
    private UUID teamId;
    private String teamName;
    private TeamLevel teamLevel;
}
