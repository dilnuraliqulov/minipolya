package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.service.BookingSeriesService;

/**
 * Generates the next week's recurring bookings. Runs daily at 06:00 Tashkent
 * (01:00 UTC); delegates to the service and is idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingSeriesJob {

    private final BookingSeriesService bookingSeriesService;

    @Scheduled(cron = "0 0 1 * * *", zone = "UTC")
    public void generateRecurringBookings() {
        bookingSeriesService.generateUpcomingBookings();
    }
}
