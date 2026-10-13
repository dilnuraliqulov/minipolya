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
}
