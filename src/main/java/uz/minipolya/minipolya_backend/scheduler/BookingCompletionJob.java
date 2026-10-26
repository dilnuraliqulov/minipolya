package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.repository.BookingRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Closes the booking lifecycle: every 15 minutes it flips {@code confirmed}
 * bookings whose {@code ends_at} has passed to {@code completed}, the terminal
 * state in the documented flow {@code pending -> confirmed -> completed}.
 *
 * <p>Without this step nothing ever reached {@code completed}, so reviews —
 * which require a completed booking at the stadium — could never be left.
 * Idempotent: once completed a booking no longer matches the query.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingCompletionJob {

    private final BookingRepository bookingRepository;

    @Scheduled(fixedRate = 900_000)
    @Transactional
    public void completePlayedBookings() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Booking> played = bookingRepository.findByStatusAndEndsAtBefore("confirmed", now);
        if (played.isEmpty()) {
            return;
        }
        played.forEach(booking -> booking.setStatus("completed"));
        bookingRepository.saveAll(played);
        log.info("Completed {} played booking(s)", played.size());
    }
}
