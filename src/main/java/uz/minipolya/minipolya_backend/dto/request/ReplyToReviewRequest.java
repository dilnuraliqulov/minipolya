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
public class ReplyToReviewRequest {

    @NotBlank
    @Size(max = 2000, message = "reply must be at most 2000 characters")
    private String reply;
}
