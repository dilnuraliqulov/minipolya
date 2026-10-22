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

    /** Supplied by the {stadiumId} path variable; a value sent in the body is overwritten. */
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
