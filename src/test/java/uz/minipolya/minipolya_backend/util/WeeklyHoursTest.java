package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.util.WeeklyHours.Day;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The working-day rule. 0 = Monday. 2026-11-20 is a Friday, 2026-11-21 a Saturday,
 * 2026-11-22 a Sunday.
 */
class WeeklyHoursTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 21);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 22);

    private static LocalTime at(int hour) {
        return LocalTime.of(hour, 0);
    }

    private static List<Day> everyDay(LocalTime open, LocalTime close) {
        List<Day> days = new ArrayList<>();
        for (int dayOfWeek = 0; dayOfWeek < 7; dayOfWeek++) {
            days.add(Day.open(dayOfWeek, open, close));
        }
        return days;
    }

    private static ZonedDateTime tashkent(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(WeeklyHours.TASHKENT);
    }

    // ---- Day ----

    @Test
    void aDayClosingAfterMidnightIsTwentyHoursLong() {
        assertThat(Day.open(0, at(8), at(4)).lengthHours()).isEqualTo(20);
    }

    @Test
    void aSameDayWindowIsTheDifference() {
        assertThat(Day.open(0, at(8), at(23)).lengthHours()).isEqualTo(15);
        assertThat(Day.open(0, at(8), at(23)).endsNextDay()).isFalse();
    }

    @Test
    void closingAtMidnightMeansTwentyFourHundred() {
        Day day = Day.open(0, at(8), LocalTime.MIDNIGHT);
        assertThat(day.lengthHours()).isEqualTo(16);
        assertThat(day.endsNextDay()).isTrue();
    }

    @Test
    void aClosedDayIsZeroHoursLongAndEndsNowhere() {
        assertThat(Day.closed(3).lengthHours()).isZero();
        assertThat(Day.closed(3).endsNextDay()).isFalse();
    }

    @Test
    void aClosedDayKeepsNoTimes() {
        assertThat(Day.closed(3).open()).isNull();
        assertThat(Day.closed(3).close()).isNull();
    }

    @Test
    void openingAndClosingAtTheSameHourIsAFullDay() {
        assertThat(Day.open(0, at(8), at(8)).lengthHours()).isEqualTo(24);
    }

    // ---- of / sameEveryDay ----

    @Test
    void sameEveryDayGivesSevenIdenticalOpenDays() {
        WeeklyHours hours = WeeklyHours.sameEveryDay(at(8), at(22));
        assertThat(hours.days()).hasSize(7).allMatch(day -> !day.closed() && day.lengthHours() == 14);
        assertThat(hours.weekHours()).isEqualTo(7 * 14);
    }

    @Test
    void daysComeBackMondayFirstWhateverOrderTheyWereGivenIn() {
        List<Day> shuffled = new ArrayList<>(everyDay(at(8), at(22)));
        shuffled.set(0, Day.closed(0));
        java.util.Collections.reverse(shuffled);
        assertThat(WeeklyHours.of(shuffled).days()).extracting(Day::dayOfWeek)
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(WeeklyHours.of(shuffled).day(0).closed()).isTrue();
    }

    @Test
    void ofRejectsAnythingButSevenDistinctDays() {
        assertThatThrownBy(() -> WeeklyHours.of(List.of(Day.open(0, at(8), at(22)))))
                .isInstanceOf(IllegalArgumentException.class);
        List<Day> duplicated = new ArrayList<>(everyDay(at(8), at(22)));
        duplicated.set(1, Day.open(0, at(8), at(22)));
        assertThatThrownBy(() -> WeeklyHours.of(duplicated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dayOfReadsTheWeekdayOfADate() {
        WeeklyHours hours = WeeklyHours.of(withClosed(5));
        assertThat(hours.dayOf(SATURDAY).dayOfWeek()).isEqualTo(5);
        assertThat(hours.dayOf(SATURDAY).closed()).isTrue();
        assertThat(hours.dayOf(FRIDAY).dayOfWeek()).isEqualTo(4);
    }

    // ---- workingDate ----

    @Test
    void theOneAmSlotOfSaturdayBelongsToSaturday() {
        WeeklyHours hours = WeeklyHours.sameEveryDay(at(8), at(2));
        assertThat(hours.workingDate(tashkent(SUNDAY, 1))).contains(SATURDAY);
    }

    @Test
    void theMidnightSlotOfSaturdayBelongsToSaturday() {
        WeeklyHours hours = WeeklyHours.sameEveryDay(at(8), at(2));
        assertThat(hours.workingDate(tashkent(SUNDAY, 0))).contains(SATURDAY);
    }

    @Test
    void theNinePmSlotBelongsToItsOwnDate() {
        WeeklyHours hours = WeeklyHours.sameEveryDay(at(8), at(2));
        assertThat(hours.workingDate(tashkent(SATURDAY, 21))).contains(SATURDAY);
    }

    @Test
    void theClosingHourItselfIsNotBookable() {
        WeeklyHours hours = WeeklyHours.sameEveryDay(at(8), at(2));
        assertThat(hours.workingDate(tashkent(SUNDAY, 2))).isEmpty();
    }

    @Test
    void anHourOutsideEveryWindowHasNoWorkingDate() {
        WeeklyHours hours = WeeklyHours.sameEveryDay(at(8), at(2));
        assertThat(hours.workingDate(tashkent(SATURDAY, 5))).isEmpty();
        assertThat(hours.workingDate(tashkent(SATURDAY, 7))).isEmpty();
    }

    @Test
    void aDayClosingAtMidnightHasNoSlotAtMidnight() {
        WeeklyHours hours = WeeklyHours.sameEveryDay(at(8), LocalTime.MIDNIGHT);
        assertThat(hours.workingDate(tashkent(SATURDAY, 23))).contains(SATURDAY);
        // The day ends exactly at midnight, so nothing after it is Saturday's; and
        // Sunday itself only opens at 08:00, so the hour belongs to no working day.
        assertThat(hours.workingDate(tashkent(SUNDAY, 0))).isEmpty();
    }

    @Test
    void aClosedDayOpensNothing() {
        WeeklyHours hours = WeeklyHours.of(withClosed(5));
        assertThat(hours.workingDate(tashkent(SATURDAY, 20))).isEmpty();
    }

    @Test
    void theNightOfAClosedDayIsNotAnotherDaysNight() {
        // Friday open 08:00-02:00, Saturday closed: Sunday 01:00 belongs to nobody
        // (Saturday never opened), while Saturday 01:00 is still Friday's.
        List<Day> days = new ArrayList<>(everyDay(at(8), at(2)));
        days.set(5, Day.closed(5));
        WeeklyHours hours = WeeklyHours.of(days);
        assertThat(hours.workingDate(tashkent(SATURDAY, 1))).contains(FRIDAY);
        assertThat(hours.workingDate(tashkent(SUNDAY, 1))).isEmpty();
    }

    @Test
    void midnightConvertsThroughTheZoneNotAFixedOffset() {
        assertThat(SUNDAY.atStartOfDay(WeeklyHours.TASHKENT).toInstant())
                .isEqualTo(Instant.parse("2026-11-21T19:00:00Z"));
    }

    private static List<Day> withClosed(int dayOfWeek) {
        List<Day> days = new ArrayList<>(everyDay(LocalTime.of(8, 0), LocalTime.of(22, 0)));
        days.set(dayOfWeek, Day.closed(dayOfWeek));
        return days;
    }
}
