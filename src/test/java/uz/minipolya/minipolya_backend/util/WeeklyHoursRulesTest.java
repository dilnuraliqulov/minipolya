package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.request.DayHoursRequest;
import uz.minipolya.minipolya_backend.dto.request.WeeklyHoursRequest;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The weekly schedule an owner or an admin may send. 0 = Monday … 6 = Sunday. */
class WeeklyHoursRulesTest {

    private static final int FRIDAY = 4;
    private static final int SATURDAY = 5;
    private static final int SUNDAY = 6;

    private static LocalTime at(int hour) {
        return LocalTime.of(hour, 0);
    }

    private static DayHoursRequest open(int dayOfWeek, LocalTime open, LocalTime close) {
        DayHoursRequest day = new DayHoursRequest();
        day.setDayOfWeek(dayOfWeek);
        day.setClosed(false);
        day.setOpenTime(open);
        day.setCloseTime(close);
        return day;
    }

    private static DayHoursRequest closed(int dayOfWeek) {
        DayHoursRequest day = new DayHoursRequest();
        day.setDayOfWeek(dayOfWeek);
        day.setClosed(true);
        return day;
    }

    /** A whole week of the same hours, ready to be amended by a test. */
    private static List<DayHoursRequest> week(LocalTime openTime, LocalTime closeTime) {
        List<DayHoursRequest> days = new ArrayList<>();
        for (int dayOfWeek = 0; dayOfWeek < 7; dayOfWeek++) {
            days.add(open(dayOfWeek, openTime, closeTime));
        }
        return days;
    }

    private static WeeklyHoursRequest request(List<DayHoursRequest> days) {
        WeeklyHoursRequest request = new WeeklyHoursRequest();
        request.setDays(days);
        return request;
    }

    @Test
    void acceptsFridayEightToTwo() {
        List<DayHoursRequest> days = week(at(8), LocalTime.MIDNIGHT);
        days.set(FRIDAY, open(FRIDAY, at(8), at(2)));
        WeeklyHours hours = WeeklyHoursRules.require(request(days));
        assertThat(hours.day(FRIDAY).lengthHours()).isEqualTo(18);
        assertThat(hours.day(SATURDAY).lengthHours()).isEqualTo(16);
    }

    @Test
    void acceptsMondayEightToMidnight() {
        WeeklyHours hours = WeeklyHoursRules.require(request(week(at(8), LocalTime.MIDNIGHT)));
        assertThat(hours.day(0).close()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(hours.day(0).lengthHours()).isEqualTo(16);
    }

    @Test
    void acceptsAClosedDay() {
        List<DayHoursRequest> days = week(at(8), at(22));
        days.set(SUNDAY, closed(SUNDAY));
        assertThat(WeeklyHoursRules.require(request(days)).day(SUNDAY).closed()).isTrue();
    }

    @Test
    void refusesAHalfHour() {
        List<DayHoursRequest> days = week(at(8), at(22));
        days.set(0, open(0, LocalTime.of(8, 30), at(22)));
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(days)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("openTime and closeTime must be on the hour");
    }

    @Test
    void refusesClosingAfterSixInTheMorning() {
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(week(at(8), at(7)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A closing time at or before the opening time means the next morning,"
                        + " so it must be 06:00 or earlier");
    }

    @Test
    void refusesOpeningAndClosingAtTheSameHour() {
        // 24 hours open, and the closing time is not 06:00 or earlier.
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(week(at(8), at(8)))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesLessThanAnHourBeforeTheNextOpening() {
        List<DayHoursRequest> days = week(at(8), LocalTime.MIDNIGHT);
        days.set(FRIDAY, open(FRIDAY, at(8), at(2)));
        days.set(SATURDAY, open(SATURDAY, at(2), at(22)));
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(days)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("There must be at least 1 hour between Friday's closing"
                        + " and Saturday's opening");
    }

    @Test
    void acceptsExactlyAnHourBetween() {
        List<DayHoursRequest> days = week(at(8), LocalTime.MIDNIGHT);
        days.set(FRIDAY, open(FRIDAY, at(8), at(2)));
        days.set(SATURDAY, open(SATURDAY, at(3), at(22)));
        assertThat(WeeklyHoursRules.require(request(days)).day(SATURDAY).open()).isEqualTo(at(3));
    }

    @Test
    void wrapsAroundTheWeek() {
        // Sunday closes at 02:00 Monday; Monday opens at 02:00 — no gap.
        List<DayHoursRequest> days = week(at(8), LocalTime.MIDNIGHT);
        days.set(SUNDAY, open(SUNDAY, at(8), at(2)));
        days.set(0, open(0, at(2), at(22)));
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(days)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("There must be at least 1 hour between Sunday's closing"
                        + " and Monday's opening");
    }

    @Test
    void aClosedNextDayNeedsNoGap() {
        List<DayHoursRequest> days = week(at(8), LocalTime.MIDNIGHT);
        days.set(FRIDAY, open(FRIDAY, at(8), at(6)));
        days.set(SATURDAY, closed(SATURDAY));
        assertThat(WeeklyHoursRules.require(request(days)).day(FRIDAY).lengthHours()).isEqualTo(22);
    }

    @Test
    void refusesEveryDayClosed() {
        List<DayHoursRequest> days = new ArrayList<>();
        for (int dayOfWeek = 0; dayOfWeek < 7; dayOfWeek++) {
            days.add(closed(dayOfWeek));
        }
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(days)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("At least one day must be open");
    }

    @Test
    void refusesSixDays() {
        List<DayHoursRequest> days = week(at(8), at(22));
        days.remove(6);
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(days)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("hours must list all 7 days of the week exactly once");
    }

    @Test
    void refusesADuplicatedDay() {
        List<DayHoursRequest> days = week(at(8), at(22));
        days.set(1, open(0, at(8), at(22)));
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(days)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("hours must list all 7 days of the week exactly once");
    }

    @Test
    void refusesADayOutsideTheWeek() {
        List<DayHoursRequest> days = week(at(8), at(22));
        days.set(1, open(7, at(8), at(22)));
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(days)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("dayOfWeek must be between 0 and 6");
    }

    @Test
    void refusesAnOpenDayWithoutTimes() {
        List<DayHoursRequest> days = week(at(8), at(22));
        days.set(2, open(2, at(8), null));
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(days)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("openTime and closeTime are required on an open day");
    }

    @Test
    void refusesNoDaysAtAll() {
        assertThatThrownBy(() -> WeeklyHoursRules.require(request(null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("hours must list all 7 days of the week exactly once");
    }

    @Test
    void sameEveryDayValidatesTheOnePair() {
        assertThat(WeeklyHoursRules.sameEveryDay(at(8), at(2)).weekHours()).isEqualTo(7 * 18);
        assertThatThrownBy(() -> WeeklyHoursRules.sameEveryDay(at(8), at(7)))
                .isInstanceOf(BadRequestException.class);
    }
}
