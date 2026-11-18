package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A stadium's whole weekly opening schedule: all seven days, each exactly once, in any
 * order. The whole schedule is always sent — there is no partial update of one day.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyHoursRequest {

    @Valid
    private List<DayHoursRequest> days;
}
