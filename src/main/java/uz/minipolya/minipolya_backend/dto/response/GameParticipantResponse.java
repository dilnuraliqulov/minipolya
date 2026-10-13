package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.time.OffsetDateTime;

/**
 * One participant row in a {@link GameDetailResponse}. Enum fields serialize to
 * their lowercase value (via {@code @JsonValue} on the enums).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameParticipantResponse {

    private Long id;
    private Long gameId;
    private Long userId;
    private String userName;
    private GameParticipant.Side side;
    private GameParticipant.Status status;
    /** Status of the participant's share payment, if any. */
    private Payment.Status paymentStatus;
    private OffsetDateTime joinedAt;
}
