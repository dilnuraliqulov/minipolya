package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTeamRequest {

    @NotBlank
    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    /**
     * The team's level: {@code havaskor}, {@code orta} or {@code professional}. Required —
     * a team now says how seriously it plays from the moment it exists. Anything else is a
     * 400 from {@code TeamLevel.fromValue}.
     */
    @NotBlank(message = "level is required")
    private String level;
}
