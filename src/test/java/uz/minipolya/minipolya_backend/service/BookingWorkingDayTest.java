package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.util.WeeklyHours;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A booking reports which working day it belongs to, so the Mini App can say
 * "Yakshanba, 16-noyabr - 00:00-01:00 (shanba kechasi)" without deriving the rule itself.
 * 2026-11-21 is a Saturday, 2026-11-22 a Sunday.
 */
class BookingWorkingDayTest {

    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 21);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 22);

    private static Stadium openPastMidnight() {
        Stadium stadium = Stadium.builder().id(UUID.randomUUID()).build();
        stadium.setWeeklyHours(WeeklyHours.sameEveryDay(LocalTime.of(8, 0), LocalTime.of(2, 0)));
        return stadium;
    }

    private static Booking bookingAt(Stadium stadium, LocalDate date, int hour) {
        OffsetDateTime startsAt = date.atTime(hour, 0).atZone(WeeklyHours.TASHKENT).toOffsetDateTime();
        return Booking.builder().id(UUID.randomUUID()).stadium(stadium).status("confirmed")
                .startsAt(startsAt).endsAt(startsAt.plusHours(1)).build();
    }

    @Test
    void aBookingAfterMidnightReportsItsWorkingDateAndFlag() {
        BookingResponse response = new BookingResponse();
        BookingResponseFactory.applyWorkingDay(response, bookingAt(openPastMidnight(), SUNDAY, 0));

        assertThat(response.getWorkingDate()).isEqualTo(SATURDAY);
        assertThat(response.getAfterMidnight()).isTrue();
    }

    @Test
    void anOrdinaryBookingReportsItsOwnDateAndNoFlag() {
        BookingResponse response = new BookingResponse();
        BookingResponseFactory.applyWorkingDay(response, bookingAt(openPastMidnight(), SATURDAY, 20));

        assertThat(response.getWorkingDate()).isEqualTo(SATURDAY);
        assertThat(response.getAfterMidnight()).isNull();
    }

    @Test
    void anElevenPmBookingIsStillItsOwnDay() {
        BookingResponse response = new BookingResponse();
        BookingResponseFactory.applyWorkingDay(response, bookingAt(openPastMidnight(), SATURDAY, 23));

        assertThat(response.getWorkingDate()).isEqualTo(SATURDAY);
        assertThat(response.getAfterMidnight()).isNull();
    }

    @Test
    void anHourOutsideEveryWindowFallsBackToItsOwnDate() {
        // A booking stored before the stadium's hours changed: 05:00 is open on no day.
        BookingResponse response = new BookingResponse();
        BookingResponseFactory.applyWorkingDay(response, bookingAt(openPastMidnight(), SUNDAY, 5));

        assertThat(response.getWorkingDate()).isEqualTo(SUNDAY);
        assertThat(response.getAfterMidnight()).isNull();
    }

    @Test
    void aBookingWithoutAStadiumOrStartSaysNothing() {
        BookingResponse response = new BookingResponse();
        BookingResponseFactory.applyWorkingDay(response, Booking.builder().build());

        assertThat(response.getWorkingDate()).isNull();
        assertThat(response.getAfterMidnight()).isNull();
    }
}
