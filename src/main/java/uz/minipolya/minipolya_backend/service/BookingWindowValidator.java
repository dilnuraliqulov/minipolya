package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.util.WeeklyHours;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Shared gate for every slot that becomes a row in {@code bookings}: app
 * bookings, manual bookings, recurring bookings and the auto-booking a
 * confirmed game creates.
 *
 * <p>A booking is exactly one 1-hour slot ({@link Booking#HOURS}): it must start on
 * the hour and fall inside the stadium's weekly schedule
 * ({@link Stadium#weeklyHours()}), which may run past midnight — an hour starting at
 * 01:00 belongs to the <b>working day</b> that opened the evening before. Overlap with
 * other bookings is caught separately by the DB EXCLUDE constraint.
 *
 * <p>The closure is checked at the <b>working day</b>, with the hour's clock time, so a
 * full-day closure on Friday also closes the hours Friday's opening runs into on
 * Saturday morning.
 */
@Service
@RequiredArgsConstructor
public class BookingWindowValidator {

    public static final String ONE_HOUR = "A booking is exactly one hour";
    public static final String NOT_OPEN = "The stadium is not open at that hour";

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
        LocalDate workingDate = workingDateOf(stadium, startsAt);
        LocalTime startLocal = startsAt.atZoneSameInstant(WeeklyHours.TASHKENT).toLocalTime();
        if (closureService.isClosedAt(stadium.getId(), workingDate, startLocal)) {
            throw new BadRequestException("Stadium is closed on " + workingDate);
        }
    }

    /**
     * Which working day's opening the hour starting at {@code startsAt} belongs to.
     *
     * @throws BadRequestException 400 when the start is off the hour, or no day of the
     *                             stadium's week is open then
     */
    public LocalDate workingDateOf(Stadium stadium, OffsetDateTime startsAt) {
        ZonedDateTime start = startsAt.atZoneSameInstant(WeeklyHours.TASHKENT);
        LocalTime startLocal = start.toLocalTime();
        if (startLocal.getMinute() != 0 || startLocal.getSecond() != 0 || startLocal.getNano() != 0) {
            throw new BadRequestException("Booking must start on the hour");
        }
        return stadium.weeklyHours().workingDate(start)
                .orElseThrow(() -> new BadRequestException(NOT_OPEN));
    }
}
