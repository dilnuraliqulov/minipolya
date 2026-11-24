package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a team's leader may change after the team exists: for now its level
 * ({@code havaskor}, {@code orta}, {@code professional}). The name is not editable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTeamRequest {

    @NotBlank(message = "level is required")
    private String level;
}
