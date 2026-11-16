package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * One day of a stadium's weekly opening schedule, 0 = Monday. A closed day carries no
 * times. A {@code closeTime} at or before {@code openTime} means the next morning
 * (Friday 08:00–02:00 closes at 02:00 on Saturday); 00:00 means midnight.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DayHoursResponse {

    private int dayOfWeek;

    private boolean closed;

    private LocalTime openTime;

    private LocalTime closeTime;
}
