package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

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

    private Long id;
    private String type;
    private String status;
    private String title;
    private String description;

    private Long stadiumId;
    private String stadiumName;
    private Long creatorId;
    private String creatorName;

    private Integer maxPlayers;
    private Integer minPlayers;
    private Integer currentPlayers;

    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;

    private Integer totalPrice;
    private Integer serviceFeePerPerson;
    private Integer creatorSharePct;

    private Long teamAId;
    private String teamAName;
    private Long teamBId;
    private String teamBName;

    @JsonProperty("isPublic")
    private boolean isPublic;

    private Long bookingId;

    /** Telegram deep link to invite friends: t.me/{bot}?start=game_{id}. */
    private String inviteLink;

    private List<GameParticipantResponse> participants;

    private OffsetDateTime createdAt;
}
