package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** R32: joining a team from its invite link's code. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinTeamRequest {

    @NotBlank
    private String code;
}
