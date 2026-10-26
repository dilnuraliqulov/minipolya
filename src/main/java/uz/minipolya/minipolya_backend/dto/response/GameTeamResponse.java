package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.GameParticipant;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One team's money in a team-vs-team game. Every amount is tiyin.
 *
 * <p>{@code refundIfCancelledNow} comes from the same code that performs a real
 * cancellation, so the figure a leader is shown is the figure they would get.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameTeamResponse {

    private GameParticipant.Side side;
    private UUID teamId;
    private String teamName;
    private UUID leaderId;
    private String leaderName;

    /** The platform fee this team paid. Not part of the pitch price. */
    private Long feeAmount;

    /** The owner advance this team paid up front. */
    private Long advanceAmount;

    /** What this team still owes on the pitch, or owed before settling. */
    private Long remainderAmount;

    /** {@code unpaid} or {@code paid}. */
    private String remainderStatus;

    /** Everything this team has actually paid through the app so far. */
    private Long paidInApp;

    /** The last moment this team can cancel and still get its money back. */
    private OffsetDateTime cancellationDeadline;

    /** Tiyin this team would get back if it cancelled right now; 0 if nothing. */
    private Long refundIfCancelledNow;
}
