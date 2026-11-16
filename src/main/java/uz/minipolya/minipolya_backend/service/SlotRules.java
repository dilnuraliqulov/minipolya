package uz.minipolya.minipolya_backend.service;

import uz.minipolya.minipolya_backend.dto.response.AvailabilityResponse.TimeSlot;
import uz.minipolya.minipolya_backend.repository.BookedSpan;
import uz.minipolya.minipolya_backend.util.WeeklyHours;
import uz.minipolya.minipolya_backend.util.WeeklyHours.Day;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one definition of a working day's slots and what state each is in, shared by the
 * availability screen and the free-slot count on the stadium list so the two can never
 * disagree.
 *
 * <p>The hours belong to the <b>working day</b> — the day whose opening started them — so
 * a day closing after midnight lists its night at the end: …, 23:00, 00:00, 01:00. Those
 * last hours fall on the next calendar date, which is what {@link #startOf} answers and
 * what a booking stores.
 */
final class SlotRules {

    private static final ZoneId TASHKENT = WeeklyHours.TASHKENT;

    private SlotRules() {
    }

    /**
     * One bookable hour of a working day, in Tashkent wall-clock time.
     *
     * @param afterMidnight whether the hour <b>starts</b> on the day after the opening —
     *                      23:00–00:00 does not, 00:00–01:00 does
     */
    record Hour(LocalTime start, LocalTime end, boolean afterMidnight) {
    }

    /**
     * Every full hour of the working day, opening first; empty when the day is closed. A
     * part hour before closing is not bookable.
     */
    static List<Hour> hoursOf(Day day) {
        List<Hour> hours = new ArrayList<>();
        LocalTime start = day.open();
        boolean afterMidnight = false;
        for (int i = 0; i < day.lengthHours(); i++) {
            LocalTime end = start.plusHours(1);
            hours.add(new Hour(start, end, afterMidnight));
            // The hour that ends at 00:00 is the last one before midnight; everything
            // after it starts on the next calendar date.
            if (end.equals(LocalTime.MIDNIGHT)) {
                afterMidnight = true;
            }
            start = end;
        }
        return hours;
    }

    /** The real instant the hour starts: on the working date, or the day after it. */
    static OffsetDateTime startOf(LocalDate workingDate, Hour hour) {
        return workingDate.plusDays(hour.afterMidnight() ? 1 : 0)
                .atTime(hour.start()).atZone(TASHKENT).toOffsetDateTime();
    }

    /**
     * Past, booked, closed or free, in that order of precedence.
     *
     * @param workingDate the day whose opening the hour belongs to
     * @param closedAt    asked with the hour's clock time, evaluated at the <b>working
     *                    date</b> — so a full-day closure on that date covers the night too
     */
    static String status(LocalDate workingDate, Hour hour, ZonedDateTime nowLocal, List<BookedSpan> booked,
                         Predicate<LocalTime> closedAt) {
        OffsetDateTime start = startOf(workingDate, hour);
        // An hour that has already started can no longer be booked.
        if (!start.isAfter(nowLocal.toOffsetDateTime())) {
            return TimeSlot.PAST;
        }
        OffsetDateTime end = start.plusHours(1);
        if (booked.stream().anyMatch(b -> b.startsAt().isBefore(end) && start.isBefore(b.endsAt()))) {
            return TimeSlot.BOOKED;
        }
        return closedAt.test(hour.start()) ? TimeSlot.CLOSED : TimeSlot.FREE;
    }
}
