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
 * Expires unpaid holds. Every minute it flips {@code pending} bookings whose
 * {@code expires_at} has passed to {@code expired}, freeing the slot. Idempotent:
 * once expired a booking no longer matches the query.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryJob {

    private final BookingRepository bookingRepository;

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expirePendingBookings() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Booking> expired = bookingRepository.findByStatusAndExpiresAtBefore("pending", now);
        if (expired.isEmpty()) {
            return;
        }
        expired.forEach(b -> b.setStatus("expired"));
        bookingRepository.saveAll(expired);
        log.info("Expired {} unpaid booking(s)", expired.size());
    }
}
