package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lean game summary for listings. {@code currentPlayers} is derived from
 * game_participants and populated by the service in step 10.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameResponse {

    private UUID id;
    private String type;
    private String status;
    private String title;
    private String stadiumName;
    private String creatorName;
    private Integer maxPlayers;
    private Integer currentPlayers;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private Long totalPrice;

    @JsonProperty("isPublic")
    private boolean isPublic;
}
