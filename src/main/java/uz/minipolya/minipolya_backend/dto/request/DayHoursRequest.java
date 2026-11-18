package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * One day of a stadium's weekly opening schedule. {@code dayOfWeek} is 0 = Monday …
 * 6 = Sunday. A closed day needs no times; an open day needs both, on the hour. A
 * {@code closeTime} at or before {@code openTime} means the next morning (06:00 at the
 * latest); 00:00 means midnight. Checked by {@code WeeklyHoursRules}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayHoursRequest {

    private Integer dayOfWeek;

    /** Absent counts as open. */
    private Boolean closed;

    private LocalTime openTime;

    private LocalTime closeTime;
}
