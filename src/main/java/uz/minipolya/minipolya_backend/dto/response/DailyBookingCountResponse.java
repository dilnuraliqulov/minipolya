package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** How many bookings start on one Tashkent day (confirmed or completed, as the overview counts). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBookingCountResponse {

    private LocalDate date;
    private long bookingCount;
}
