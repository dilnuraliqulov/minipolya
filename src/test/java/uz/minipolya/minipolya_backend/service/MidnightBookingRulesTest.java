package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.scheduler.BookingExpiryJob;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;
import uz.minipolya.minipolya_backend.util.WeeklyHours;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bookings keep storing the real start and end instants, whatever working day they are
 * listed under, so everything built on instants needs no special case for a stadium open
 * past midnight. These tests prove that rather than change it: a failure here is a bug in
 * the class it names, not in this file.
 *
 * <p>2026-11-20 is a Friday, 2026-11-21 a Saturday.
 */
@DatabaseTest
class MidnightBookingRulesTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 21);

    @Autowired
    private TestData data;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private StadiumRepository stadiumRepository;
    @Autowired
    private BookingWindowValidator windowValidator;
    @Autowired
    private CancellationPolicy cancellationPolicy;
    @Autowired
    private BookingExpiryJob expiryJob;

    private User owner;
    private User player;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        owner = data.owner("Midnight Owner");
        player = data.user("Midnight Player");
        stadium = data.stadium(owner, "Midnight Arena");
        // Friday 08:00-02:00; the other days 08:00-00:00, so Saturday opens at 08:00.
        stadium.setWeeklyHours(WeeklyHours.of(weekWithFridayLate()));
        stadium = stadiumRepository.saveAndFlush(stadium);
    }

    private static java.util.List<WeeklyHours.Day> weekWithFridayLate() {
        java.util.List<WeeklyHours.Day> days = new java.util.ArrayList<>();
        for (int dayOfWeek = 0; dayOfWeek < 7; dayOfWeek++) {
            days.add(WeeklyHours.Day.open(dayOfWeek, LocalTime.of(8, 0), LocalTime.MIDNIGHT));
        }
        days.set(4, WeeklyHours.Day.open(4, LocalTime.of(8, 0), LocalTime.of(2, 0)));
        return days;
    }

    /** The real instant of an hour on a calendar date in Tashkent. */
    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(WeeklyHours.TASHKENT).toOffsetDateTime();
    }

    // ---- the EXCLUDE constraint ----

    @Test
    void theExcludeConstraintStillRejectsAnOverlapAfterMidnight() {
        data.booking(stadium, player, at(SATURDAY, 1), "confirmed");

        assertThatThrownBy(() -> {
            data.booking(stadium, player, at(SATURDAY, 1), "confirmed");
            bookingRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theNightHourAndTheNextMorningAreAdjacentNotOverlapping() {
        // Friday's last slot is 01:00-02:00 on Saturday; Saturday opens at 08:00, so the
        // two never meet - but even hour by hour the constraint only rejects an overlap.
        data.booking(stadium, player, at(SATURDAY, 1), "confirmed");
        assertThatCode(() -> {
            data.booking(stadium, player, at(SATURDAY, 8), "confirmed");
            bookingRepository.flush();
        }).doesNotThrowAnyException();
    }

    @Test
    void anHourPastTheNightClosingIsRefusedBeforeItReachesTheDatabase() {
        // 02:00 on Saturday is past Friday's closing and before Saturday's opening.
        assertThatThrownBy(() -> windowValidator.validate(stadium, at(SATURDAY, 2)))
                .hasMessage(BookingWindowValidator.NOT_OPEN);
    }

    @Test
    void theOneAmHourOfTheWorkingDayIsAccepted() {
        assertThatCode(() -> windowValidator.validate(stadium, at(SATURDAY, 1)))
                .doesNotThrowAnyException();
        assertThat(windowValidator.workingDateOf(stadium, at(SATURDAY, 1))).isEqualTo(FRIDAY);
    }

    // ---- cancellation: an instant, not a day ----

    @Test
    void theCancellationDeadlineOfANightBookingIsTwentyFourHoursBeforeItReallyStarts() {
        Booking booking = data.booking(stadium, player, at(SATURDAY, 0), "confirmed");

        // The hour really starts at 00:00 on Saturday, so the deadline is 24 hours before
        // that - 00:00 on Friday - whichever working day the slot is listed under.
        assertThat(cancellationPolicy.deadline(booking)).isEqualTo(at(FRIDAY, 0));
        assertThat(cancellationPolicy.deadline(booking).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-11-19T19:00:00Z"));
    }

    @Test
    void cancellingJustInsideTheDeadlineOfANightBookingStillRefunds() {
        Booking booking = data.booking(stadium, player, at(SATURDAY, 0), "confirmed");
        OffsetDateTime deadline = cancellationPolicy.deadline(booking);

        assertThat(cancellationPolicy.refundable(booking, deadline.minusMinutes(1))).isTrue();
        assertThat(cancellationPolicy.refundable(booking, deadline)).isTrue();
        assertThat(cancellationPolicy.refundable(booking, deadline.plusMinutes(1))).isFalse();
    }

    @Test
    void theThreeHourCutoffOfANightGameIsAnInstantToo() {
        // A 01:00 team game closes to opponents at 22:00 the previous evening.
        OffsetDateTime start = at(SATURDAY, 1);
        assertThat(start.minusHours(3).toInstant()).isEqualTo(java.time.Instant.parse("2026-11-20T17:00:00Z"));
        assertThat(cancellationPolicy.deadline(start, 3).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-11-20T17:00:00Z"));
    }

    // ---- the expiry job ----

    @Test
    void theExpiryJobExpiresAnUnpaidNightBooking() {
        Booking booking = bookingRepository.saveAndFlush(Booking.builder()
                .stadium(stadium).user(player).source("app").status("pending")
                .startsAt(at(SATURDAY, 1)).endsAt(at(SATURDAY, 2)).durationHours(1)
                .price(20_000_000L)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .build());

        expiryJob.expirePendingBookings();

        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo("expired");
    }
}
