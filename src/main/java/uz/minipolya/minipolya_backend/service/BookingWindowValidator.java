package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.minipolya.minipolya_backend.entity.Booking;
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
 * <p>A booking is exactly one 1-hour slot ({@link Booking#HOURS}): it must start on
 * the hour, sit entirely within the stadium's Tashkent open–close window, and not
 * fall in a closure. Overlap with other bookings is caught separately by the DB
 * EXCLUDE constraint. The open–close check mirrors {@link AvailabilityService}: that
 * endpoint treats close_time as a same-day bound and produces no slots when
 * close_time &lt;= open_time, so such a window is unbookable here too.
 */
@Service
@RequiredArgsConstructor
public class BookingWindowValidator {

    public static final String ONE_HOUR = "A booking is exactly one hour";

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private final StadiumClosureService closureService;

    /**
     * The length a request may still send ({@code durationHours} / {@code duration}):
     * absent or 1 is fine; anything else is 400 {@value #ONE_HOUR}.
     */
    public static void requireOneHour(Integer hours) {
        if (hours != null && hours != Booking.HOURS) {
            throw new BadRequestException(ONE_HOUR);
        }
    }

    public void validate(Stadium stadium, OffsetDateTime startsAt) {
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
        ZonedDateTime end = start.plusHours(Booking.HOURS);
        boolean endsSameDay = end.toLocalDate().equals(start.toLocalDate());
        if (!endsSameDay || end.toLocalTime().isAfter(close)) {
            throw new BadRequestException("Booking must end by " + close);
        }

        if (closureService.isClosedAt(stadium.getId(), start.toLocalDate(), startLocal)) {
            throw new BadRequestException("Stadium is closed on " + start.toLocalDate());
        }
    }
}
