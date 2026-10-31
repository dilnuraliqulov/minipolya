package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full game view. {@code currentPlayers} and {@code participants} are derived
 * from game_participants and populated by the service in step 10.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameDetailResponse {

    private UUID id;
    private String type;
    private String status;
    private String title;
    private String description;

    private UUID stadiumId;
    private String stadiumName;
    private UUID creatorId;
    private String creatorName;

    private Integer maxPlayers;
    private Integer minPlayers;
    private Integer currentPlayers;

    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;

    private Long totalPrice;
    private Long serviceFeePerPerson;

    /**
     * A casual or open game: what one player's share costs by card (pitch share plus
     * service fee, grossed up). Absent on a team game, whose teams[] carry their own.
     */
    private Long price;
    /** price − the share's net. */
    private Long cardFee;

    private UUID teamAId;
    private String teamAName;
    private UUID teamBId;
    private String teamBName;

    @JsonProperty("isPublic")
    private boolean isPublic;

    private UUID bookingId;

    /** Telegram deep link to invite friends: t.me/{bot}?start=game_{id}. */
    private String inviteLink;

    /** team_vs_team only: each team's fee, advance, remainder and refund position. */
    private List<GameTeamResponse> teams;

    private List<GameParticipantResponse> participants;

    private OffsetDateTime createdAt;
}
