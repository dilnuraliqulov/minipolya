package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.response.AvailabilityResponse.TimeSlot;
import uz.minipolya.minipolya_backend.repository.BookedSpan;
import uz.minipolya.minipolya_backend.util.WeeklyHours;
import uz.minipolya.minipolya_backend.util.WeeklyHours.Day;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlotRulesTest {

    private static final ZoneId TASHKENT = WeeklyHours.TASHKENT;
    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);
    private static final ZonedDateTime NOW = DAY.atTime(12, 30).atZone(TASHKENT);
    private static final SlotRules.Hour TEN = hour(10, 11, false);
    private static final SlotRules.Hour FOURTEEN = hour(14, 15, false);

    private static SlotRules.Hour hour(int start, int end, boolean afterMidnight) {
        return new SlotRules.Hour(LocalTime.of(start, 0), LocalTime.of(end, 0), afterMidnight);
    }

    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    private static OffsetDateTime at(int hour) {
        return at(DAY, hour);
    }

    // ---- hoursOf ----

    @Test
    void aDayClosingAtTwoListsTheNightLast() {
        assertThat(SlotRules.hoursOf(Day.open(0, LocalTime.of(22, 0), LocalTime.of(2, 0))))
                .containsExactly(hour(22, 23, false), hour(23, 0, false),
                        hour(0, 1, true), hour(1, 2, true));
    }

    @Test
    void aDayClosingAtMidnightStopsAtTwentyThree() {
        assertThat(SlotRules.hoursOf(Day.open(0, LocalTime.of(22, 0), LocalTime.MIDNIGHT)))
                .containsExactly(hour(22, 23, false), hour(23, 0, false));
    }

    @Test
    void anOrdinaryDayIsCutFromOpeningToClosing() {
        assertThat(SlotRules.hoursOf(Day.open(0, LocalTime.of(8, 0), LocalTime.of(11, 0))))
                .containsExactly(hour(8, 9, false), hour(9, 10, false), hour(10, 11, false));
    }

    @Test
    void aClosedDayHasNoHours() {
        assertThat(SlotRules.hoursOf(Day.closed(3))).isEmpty();
    }

    @Test
    void aPartHourBeforeClosingIsNotBookable() {
        assertThat(SlotRules.hoursOf(Day.open(0, LocalTime.of(8, 0), LocalTime.of(10, 30))))
                .containsExactly(hour(8, 9, false), hour(9, 10, false));
    }

    // ---- startOf ----

    @Test
    void anAfterMidnightHourStartsOnTheNextCalendarDay() {
        assertThat(SlotRules.startOf(LocalDate.of(2026, 11, 21), hour(1, 2, true)))
                .isEqualTo(OffsetDateTime.parse("2026-11-21T20:00:00Z"));
    }

    @Test
    void anOrdinaryHourStartsOnTheWorkingDate() {
        assertThat(SlotRules.startOf(LocalDate.of(2026, 11, 21), hour(20, 21, false)))
                .isEqualTo(OffsetDateTime.parse("2026-11-21T15:00:00Z"));
    }

    // ---- status ----

    @Test
    void anHourThatHasStartedTodayIsPastBeforeAnythingElse() {
        BookedSpan booked = new BookedSpan(UUID.randomUUID(), at(10), at(11));
        assertThat(SlotRules.status(DAY, TEN, NOW, List.of(booked), t -> true)).isEqualTo(TimeSlot.PAST);
    }

    @Test
    void theSameHourOnAnotherDayIsNotPast() {
        assertThat(SlotRules.status(DAY.plusDays(1), TEN, NOW, List.of(), t -> false)).isEqualTo(TimeSlot.FREE);
    }

    @Test
    void anAfterMidnightHourThatHasStartedIsPast() {
        // 00:30 on 2 December: the working day is 1 December, whose 00:00 slot has begun.
        ZonedDateTime justAfterMidnight = DAY.plusDays(1).atTime(0, 30).atZone(TASHKENT);
        assertThat(SlotRules.status(DAY, hour(0, 1, true), justAfterMidnight, List.of(), t -> false))
                .isEqualTo(TimeSlot.PAST);
    }

    @Test
    void anAfterMidnightHourStillToComeIsNotPast() {
        ZonedDateTime justAfterMidnight = DAY.plusDays(1).atTime(0, 30).atZone(TASHKENT);
        assertThat(SlotRules.status(DAY, hour(1, 2, true), justAfterMidnight, List.of(), t -> false))
                .isEqualTo(TimeSlot.FREE);
    }

    @Test
    void anAfterMidnightHourIsBookedByABookingOnTheNextDate() {
        BookedSpan booked = new BookedSpan(UUID.randomUUID(), at(DAY.plusDays(1), 1), at(DAY.plusDays(1), 2));
        assertThat(SlotRules.status(DAY, hour(1, 2, true), NOW, List.of(booked), t -> false))
                .isEqualTo(TimeSlot.BOOKED);
    }

    @Test
    void anAfterMidnightHourIsNotBookedByTheSameClockHourOnTheWorkingDate() {
        BookedSpan booked = new BookedSpan(UUID.randomUUID(), at(DAY, 1), at(DAY, 2));
        assertThat(SlotRules.status(DAY, hour(1, 2, true), NOW, List.of(booked), t -> false))
                .isEqualTo(TimeSlot.FREE);
    }

    @Test
    void bookedComesBeforeClosed() {
        BookedSpan booked = new BookedSpan(UUID.randomUUID(), at(14), at(15));
        assertThat(SlotRules.status(DAY, FOURTEEN, NOW, List.of(booked), t -> true)).isEqualTo(TimeSlot.BOOKED);
    }

    @Test
    void closedWhenOnlyTheClosureCovers() {
        assertThat(SlotRules.status(DAY, FOURTEEN, NOW, List.of(), t -> true)).isEqualTo(TimeSlot.CLOSED);
    }

    @Test
    void aClosureOfTheWorkingDayClosesItsNightToo() {
        // The predicate is asked at the working day with the hour's clock time.
        assertThat(SlotRules.status(DAY, hour(1, 2, true), NOW, List.of(), t -> true))
                .isEqualTo(TimeSlot.CLOSED);
    }

    @Test
    void anAdjacentBookingLeavesTheHourFree() {
        BookedSpan before = new BookedSpan(UUID.randomUUID(), at(13), at(14));
        BookedSpan after = new BookedSpan(UUID.randomUUID(), at(15), at(16));
        assertThat(SlotRules.status(DAY, FOURTEEN, NOW, List.of(before, after), t -> false)).isEqualTo(TimeSlot.FREE);
    }
}
