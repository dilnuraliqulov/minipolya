package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.response.AvailabilityResponse.TimeSlot;
import uz.minipolya.minipolya_backend.repository.BookedSpan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlotRulesTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);
    private static final ZonedDateTime NOW = DAY.atTime(12, 30).atZone(TASHKENT);
    private static final SlotRules.Hour TEN = new SlotRules.Hour(LocalTime.of(10, 0), LocalTime.of(11, 0));
    private static final SlotRules.Hour FOURTEEN = new SlotRules.Hour(LocalTime.of(14, 0), LocalTime.of(15, 0));

    private static OffsetDateTime at(int hour) {
        return DAY.atTime(hour, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    @Test
    void hoursAreCutFromOpeningToClosingAndAPartHourIsDropped() {
        assertThat(SlotRules.hoursOf(LocalTime.of(8, 0), LocalTime.of(10, 30)))
                .containsExactly(new SlotRules.Hour(LocalTime.of(8, 0), LocalTime.of(9, 0)),
                        new SlotRules.Hour(LocalTime.of(9, 0), LocalTime.of(10, 0)));
    }

    @Test
    void closingJustBeforeMidnightEnds() {
        assertThat(SlotRules.hoursOf(LocalTime.of(22, 0), LocalTime.of(23, 59)))
                .containsExactly(new SlotRules.Hour(LocalTime.of(22, 0), LocalTime.of(23, 0)));
    }

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
    void bookedComesBeforeClosed() {
        BookedSpan booked = new BookedSpan(UUID.randomUUID(), at(14), at(15));
        assertThat(SlotRules.status(DAY, FOURTEEN, NOW, List.of(booked), t -> true)).isEqualTo(TimeSlot.BOOKED);
    }

    @Test
    void closedWhenOnlyTheClosureCovers() {
        assertThat(SlotRules.status(DAY, FOURTEEN, NOW, List.of(), t -> true)).isEqualTo(TimeSlot.CLOSED);
    }

    @Test
    void anAdjacentBookingLeavesTheHourFree() {
        BookedSpan before = new BookedSpan(UUID.randomUUID(), at(13), at(14));
        BookedSpan after = new BookedSpan(UUID.randomUUID(), at(15), at(16));
        assertThat(SlotRules.status(DAY, FOURTEEN, NOW, List.of(before, after), t -> false)).isEqualTo(TimeSlot.FREE);
    }
}
