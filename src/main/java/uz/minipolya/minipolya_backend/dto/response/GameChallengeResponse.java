package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One open challenge: a team-vs-team game with a team waiting for an opponent.
 * Everything the app's challenge card shows, including what the joining team
 * would pay on the spot. All amounts are tiyin.
 *
 * <p>{@code stadiumAddress} stands in for a district — stadiums have no
 * district column, only an address.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameChallengeResponse {

    private UUID gameId;
    private String title;

    private UUID waitingTeamId;
    private String waitingTeamName;

    private UUID stadiumId;
    private String stadiumName;
    private String stadiumAddress;
    private String format;

    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private Integer durationHours;

    /** The whole pitch price for the game. */
    private Long totalPrice;

    /** The platform fee the joining team pays. */
    private Long feeAmount;

    /** The owner advance the joining team pays. */
    private Long advanceAmount;

    /** What the joining team's card is charged in the app: fee + advance. */
    private Long amountToPayNow;

    /** amountToPayNow − (feeAmount + advanceAmount): what paying by card adds. */
    private Long cardFee;

    /** What the joining team would still owe afterwards. */
    private Long remainderAmount;
}
