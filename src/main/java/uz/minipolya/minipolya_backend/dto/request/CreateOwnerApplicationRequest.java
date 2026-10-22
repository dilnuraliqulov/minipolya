package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOwnerApplicationRequest {

    @NotBlank
    private String businessName;

    @NotBlank
    private String businessAddress;

    @NotBlank
    private String phone;

    private String description;

    @NotNull
    @Min(1)
    private Integer stadiumCount;
}
