package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The stadium owner marking one team's remainder as received in cash. Each
 * team is settled separately, so the side is required.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameCashRequest {

    @NotNull
    @Pattern(regexp = "a|b", message = "side must be 'a' or 'b'")
    private String side;
}
