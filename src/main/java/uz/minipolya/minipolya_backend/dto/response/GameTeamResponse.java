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

    /** What this team pays in the app on joining. */
    private Long price;

    /**
     * What this team owes (or owed, once {@code remainderStatus} is {@code paid}) after
     * joining — the same amount in the app or in cash.
     */
    private Long remainderAmount;

    /** {@code unpaid} or {@code paid}. */
    private String remainderStatus;

    /** Everything this team has actually paid through the app so far. */
    private Long paidInApp;

    /**
     * What is on hold on this team's card right now: a hold that was created and is not yet
     * captured or released ({@code held}, or {@code capture_pending} while the capture is
     * asked for) and has not lapsed. 0 otherwise. Always present.
     */
    private Long heldAmount;

    /** Admin view only: the platform's fixed fee in this team's payment (what it nets). */
    private Long platformFee;
    /** Admin view only: the owner's advance in this team's payment, in the owner's terms. */
    private Long ownerAdvance;
    /** Admin view only: this team's remainder in the owner's terms. */
    private Long ownerRemainder;
    /** Admin view only: what the owner receives of this team's payments, after ATMOS. */
    private Long ownerReceives;
    /** Admin view only: what ATMOS takes of this team's card payments. */
    private Long atmosFee;

    /** The last moment this team can cancel and still get its money back. */
    private OffsetDateTime cancellationDeadline;

    /** Tiyin this team would get back if it cancelled right now; 0 if nothing. */
    private Long refundIfCancelledNow;
}
