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

    /** What both teams pay for this game altogether: each side's joining price and remainder. */
    private Long totalPrice;

    /** What the joining team pays in the app on joining. */
    private Long amountToPayNow;

    /** What the joining team would still owe afterwards — the same in the app or in cash. */
    private Long remainderAmount;
}
