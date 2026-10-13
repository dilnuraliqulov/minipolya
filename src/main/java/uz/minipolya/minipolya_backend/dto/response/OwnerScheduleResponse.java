package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerScheduleResponse {

    private LocalDate date;
    private List<BookingResponse> bookings;
    private int totalSlots;
    private int bookedSlots;
    private int revenue;
}
