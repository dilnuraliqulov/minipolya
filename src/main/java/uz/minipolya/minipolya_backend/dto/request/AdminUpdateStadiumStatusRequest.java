package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUpdateStadiumStatusRequest {

    @NotBlank
    @Pattern(regexp = "approved|blocked|pending|rejected",
            message = "status must be approved, blocked, pending or rejected")
    private String status;

    private String reason;
}
