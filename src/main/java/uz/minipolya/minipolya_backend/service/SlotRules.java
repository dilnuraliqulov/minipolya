package uz.minipolya.minipolya_backend.service;

import uz.minipolya.minipolya_backend.dto.response.AvailabilityResponse.TimeSlot;
import uz.minipolya.minipolya_backend.repository.BookedSpan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one definition of a day's slots and what state each is in, shared by the availability
 * screen and the free-slot count on the stadium list so the two can never disagree.
 */
final class SlotRules {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private SlotRules() {
    }

    /** One bookable hour, Tashkent local time. */
    record Hour(LocalTime start, LocalTime end) {
    }

    /** Every full hour from opening to closing; a part hour before closing is not bookable. */
    static List<Hour> hoursOf(LocalTime open, LocalTime close) {
        List<Hour> hours = new ArrayList<>();
        LocalTime start = open;
        while (start.isBefore(close)) {
            LocalTime end = start.plusHours(1);
            // LocalTime wraps at midnight: without the first check, a stadium closing at 23:59 never ends.
            if (!end.isAfter(start) || end.isAfter(close)) {
                break;
            }
            hours.add(new Hour(start, end));
            start = end;
        }
        return hours;
    }

    /** Past, booked, closed or free, in that order of precedence. */
    static String status(LocalDate date, Hour hour, ZonedDateTime nowLocal, List<BookedSpan> booked,
                         Predicate<LocalTime> closedAt) {
        // Today, an hour that has already started can no longer be booked.
        if (date.equals(nowLocal.toLocalDate()) && !hour.start().isAfter(nowLocal.toLocalTime())) {
            return TimeSlot.PAST;
        }
        OffsetDateTime start = date.atTime(hour.start()).atZone(TASHKENT).toOffsetDateTime();
        OffsetDateTime end = date.atTime(hour.end()).atZone(TASHKENT).toOffsetDateTime();
        if (booked.stream().anyMatch(b -> b.startsAt().isBefore(end) && start.isBefore(b.endsAt()))) {
            return TimeSlot.BOOKED;
        }
        return closedAt.test(hour.start()) ? TimeSlot.CLOSED : TimeSlot.FREE;
    }
}
