package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinGameRequest {

    @NotNull
    private Long gameId;

    /** Only for team_vs_team when Team B's leader joins: "a" or "b". */
    @Pattern(regexp = "a|b", message = "side must be 'a' or 'b'")
    private String side;
}
