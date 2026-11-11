package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Where a game is played: the stadium's location as a game screen shows it to everyone
 * who can open the game (both teams, in every state) and on the challenge board.
 * Built by {@code StadiumMapper.toLocationResponse}; {@code coverPhotoUrl} is set by the
 * service, a page of stadiums at once.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StadiumLocationResponse {

    private UUID id;
    private String name;
    private String address;
    /** One of the twelve Tashkent districts; absent for stadiums added before districts. */
    private String district;
    /** "Moʻljal": a landmark near the pitch; absent when none. */
    private String landmark;
    private BigDecimal latitude;
    private BigDecimal longitude;
    /** The stadium's cover photo (lowest position); absent when it has none. */
    private String coverPhotoUrl;
    private String format;
}
