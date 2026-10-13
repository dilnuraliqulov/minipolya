package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePromotionRequest {

    @NotNull
    private Long stadiumId;

    @NotBlank
    private String name;

    private String description;

    @NotNull
    @Min(1)
    @Max(100)
    private Integer discountPercent;

    @NotNull
    private OffsetDateTime startsAt;

    @NotNull
    private OffsetDateTime endsAt;

    /** Null = all days. 0 = Monday .. 6 = Sunday. */
    @Min(0)
    @Max(6)
    private Integer dayOfWeek;

    /** Null = all hours. */
    private LocalTime startTime;
    private LocalTime endTime;
}
