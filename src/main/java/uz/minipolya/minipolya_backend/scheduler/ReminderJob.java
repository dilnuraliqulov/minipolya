package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.NotificationRepository;
import uz.minipolya.minipolya_backend.service.NotificationService;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Sends a reminder ~2 hours before each confirmed booking. Runs every 30
 * minutes and targets starts within [now+1.5h, now+2.5h], covering the window
 * once; idempotent via an existing-reminder check per booking.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderJob {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BookingRepository bookingRepository;
    private final NotificationRepository notificationRepository;
    private final GameParticipantRepository participantRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "0 */30 * * * *")
    public void sendReminders() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Booking> upcoming = bookingRepository.findByStatusAndStartsAtBetween(
                "confirmed", now.plusMinutes(90), now.plusMinutes(150));

        for (Booking booking : upcoming) {
            if (notificationRepository.existsByReferenceTypeAndReferenceIdAndType(
                    Notification.ReferenceType.BOOKING, booking.getId(), Notification.Type.BOOKING_REMINDER)) {
                continue; // already reminded for this booking
            }
            String localTime = booking.getStartsAt().atZoneSameInstant(TASHKENT).format(TIME);
            String message = "Напоминание: у вас бронь в «" + booking.getStadium().getName()
                    + "» сегодня в " + localTime + ".";

            if (booking.getUser() != null) {
                safeNotify(booking.getUser().getId(), booking.getId(), message);
            }
            if (booking.getGameId() != null) {
                for (GameParticipant participant : participantRepository.findByGameId(booking.getGameId())) {
                    safeNotify(participant.getUser().getId(), booking.getId(), message);
                }
            }
        }
    }

    private void safeNotify(Long userId, Long bookingId, String message) {
        try {
            notificationService.notify(userId, Notification.Type.BOOKING_REMINDER, "Напоминание о брони",
                    message, Notification.ReferenceType.BOOKING, bookingId);
        } catch (Exception ex) {
            log.warn("Reminder notification failed for user {}: {}", userId, ex.toString());
        }
    }
}
