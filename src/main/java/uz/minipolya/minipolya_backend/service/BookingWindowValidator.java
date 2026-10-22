package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Shared gate for every slot that becomes a row in {@code bookings}: app
 * bookings, manual bookings, recurring bookings and the auto-booking a
 * confirmed game creates.
 *
 * <p>A booking is a run of consecutive whole 1-hour slots: it must start on the
 * hour, be at least 1 hour, sit entirely within the stadium's Tashkent
 * open–close window, and not touch any closure. Overlap with other bookings is
 * caught separately by the DB EXCLUDE constraint. The open–close check mirrors
 * {@link AvailabilityService}: that endpoint treats close_time as a same-day
 * bound and produces no slots when close_time &lt;= open_time, so such a window
 * is unbookable here too.
 */
@Service
@RequiredArgsConstructor
public class BookingWindowValidator {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private final StadiumClosureService closureService;

    public void validate(Stadium stadium, OffsetDateTime startsAt, int duration) {
        if (duration < 1) {
            throw new BadRequestException("Duration must be at least 1 hour");
        }

        ZonedDateTime start = startsAt.atZoneSameInstant(TASHKENT);
        LocalTime startLocal = start.toLocalTime();
        if (startLocal.getMinute() != 0 || startLocal.getSecond() != 0 || startLocal.getNano() != 0) {
            throw new BadRequestException("Booking must start on the hour");
        }

        LocalTime open = stadium.getOpenTime();
        LocalTime close = stadium.getCloseTime();
        if (!open.isBefore(close)) {
            throw new BadRequestException("Stadium has no bookable hours");
        }
        if (startLocal.isBefore(open)) {
            throw new BadRequestException("Booking must start at or after " + open);
        }
        ZonedDateTime end = start.plusHours(duration);
        boolean endsSameDay = end.toLocalDate().equals(start.toLocalDate());
        if (!endsSameDay || end.toLocalTime().isAfter(close)) {
            throw new BadRequestException("Booking must end by " + close);
        }

        for (int hour = 0; hour < duration; hour++) {
            ZonedDateTime slot = start.plusHours(hour);
            if (closureService.isClosedAt(stadium.getId(), slot.toLocalDate(), slot.toLocalTime())) {
                throw new BadRequestException("Stadium is closed on " + slot.toLocalDate());
            }
        }
    }
}
