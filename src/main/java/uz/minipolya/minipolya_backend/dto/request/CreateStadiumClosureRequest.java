package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateStadiumClosureRequest {

    @NotNull
    private UUID stadiumId;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    /** Null (with endTime) means a full-day closure. */
    private LocalTime startTime;
    private LocalTime endTime;

    private String reason;
}
