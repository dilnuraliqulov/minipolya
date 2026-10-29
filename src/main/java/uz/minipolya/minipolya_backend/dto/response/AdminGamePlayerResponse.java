package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.GameParticipant;

import java.util.UUID;

/** A player in a game, as the admin panel lists them. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminGamePlayerResponse {

    private UUID userId;
    private String userName;
    /** {@code joined}, {@code paid}, {@code cancelled} or {@code refunded}. */
    private GameParticipant.Status status;
}
