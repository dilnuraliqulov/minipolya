package uz.minipolya.minipolya_backend.util;

import uz.minipolya.minipolya_backend.dto.request.DayHoursRequest;
import uz.minipolya.minipolya_backend.dto.request.WeeklyHoursRequest;
import uz.minipolya.minipolya_backend.dto.response.DayHoursResponse;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.util.WeeklyHours.Day;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules for a stadium's weekly opening schedule, in one place so the owner's
 * create/update and the admin's edit agree — every check is a 400 with the message the
 * API documents.
 *
 * <ul>
 *   <li>all seven days, each exactly once; at least one of them open;</li>
 *   <li>an open day has both times, both on the hour;</li>
 *   <li>{@code closeTime} after {@code openTime} is the same day; at or before it means
 *       the next morning and must be 06:00 or earlier; 00:00 means midnight;</li>
 *   <li>a day is open at most 24 hours;</li>
 *   <li>at least 1 hour between a day's closing and the next day's opening, around the
 *       week.</li>
 * </ul>
 */
public final class WeeklyHoursRules {

    /** The latest a day may close when it closes after midnight. */
    public static final LocalTime LATEST_AFTER_MIDNIGHT_CLOSE = LocalTime.of(6, 0);

    /** The smallest gap between one day's closing and the next day's opening, in hours. */
    public static final int MIN_GAP_HOURS = 1;

    public static final String ON_THE_HOUR = "openTime and closeTime must be on the hour";
    public static final String ALL_SEVEN_DAYS = "hours must list all 7 days of the week exactly once";
    public static final String AT_LEAST_ONE_OPEN = "At least one day must be open";
    public static final String TIMES_REQUIRED = "openTime and closeTime are required on an open day";
    public static final String DAY_OF_WEEK_RANGE = "dayOfWeek must be between 0 and 6";
    public static final String CLOSE_TOO_LATE =
            "A closing time at or before the opening time means the next morning,"
                    + " so it must be 06:00 or earlier";
    public static final String TOO_LONG = "A day cannot be open for more than 24 hours";

    private WeeklyHoursRules() {
    }

    /** The schedule a request asks for, checked. */
    public static WeeklyHours require(WeeklyHoursRequest request) {
        List<DayHoursRequest> days = request == null ? null : request.getDays();
        if (days == null || days.size() != WeeklyHours.DAYS) {
            throw new BadRequestException(ALL_SEVEN_DAYS);
        }
        Set<Integer> seen = new HashSet<>();
        List<Day> parsed = new ArrayList<>(WeeklyHours.DAYS);
        for (DayHoursRequest day : days) {
            Integer dayOfWeek = day == null ? null : day.getDayOfWeek();
            if (dayOfWeek == null) {
                throw new BadRequestException(ALL_SEVEN_DAYS);
            }
            if (dayOfWeek < 0 || dayOfWeek >= WeeklyHours.DAYS) {
                throw new BadRequestException(DAY_OF_WEEK_RANGE);
            }
            if (!seen.add(dayOfWeek)) {
                throw new BadRequestException(ALL_SEVEN_DAYS);
            }
            parsed.add(Boolean.TRUE.equals(day.getClosed())
                    ? Day.closed(dayOfWeek)
                    : openDay(dayOfWeek, day.getOpenTime(), day.getCloseTime()));
        }
        return validate(WeeklyHours.of(parsed));
    }

    /** The one pair of hours a stadium without a schedule means: the same every day. */
    public static WeeklyHours sameEveryDay(LocalTime open, LocalTime close) {
        if (open == null || close == null) {
            throw new BadRequestException(TIMES_REQUIRED);
        }
        return validate(WeeklyHours.sameEveryDay(requireOneDay(open, close), close));
    }

    /** The response rows for a schedule, Monday first. */
    public static List<DayHoursResponse> toResponse(WeeklyHours hours) {
        return hours.days().stream()
                .map(day -> DayHoursResponse.builder()
                        .dayOfWeek(day.dayOfWeek())
                        .closed(day.closed())
                        .openTime(day.open())
                        .closeTime(day.close())
                        .build())
                .toList();
    }

    /**
     * A schedule as one readable audit value, Monday first:
     * {@code "Mon 08:00-00:00, ..., Fri 08:00-02:00, Sun closed"}.
     */
    public static String describe(WeeklyHours hours) {
        List<String> parts = new ArrayList<>(WeeklyHours.DAYS);
        for (Day day : hours.days()) {
            String name = shortName(day.dayOfWeek());
            parts.add(day.closed()
                    ? name + " closed"
                    : name + " " + day.open() + "-" + day.close());
        }
        return String.join(", ", parts);
    }

    // ---- The individual rules ----

    private static Day openDay(int dayOfWeek, LocalTime open, LocalTime close) {
        if (open == null || close == null) {
            throw new BadRequestException(TIMES_REQUIRED);
        }
        return Day.open(dayOfWeek, requireOneDay(open, close), close);
    }

    /** Both on the hour, a closing after midnight no later than 06:00, at most 24 hours. */
    private static LocalTime requireOneDay(LocalTime open, LocalTime close) {
        if (!onTheHour(open) || !onTheHour(close)) {
            throw new BadRequestException(ON_THE_HOUR);
        }
        boolean nextMorning = !close.isAfter(open) && !close.equals(LocalTime.MIDNIGHT);
        if (nextMorning && close.isAfter(LATEST_AFTER_MIDNIGHT_CLOSE)) {
            throw new BadRequestException(CLOSE_TOO_LATE);
        }
        if (Day.open(0, open, close).lengthHours() > WeeklyHours.HOURS_IN_DAY) {
            throw new BadRequestException(TOO_LONG);
        }
        return open;
    }

    private static WeeklyHours validate(WeeklyHours hours) {
        if (hours.allClosed()) {
            throw new BadRequestException(AT_LEAST_ONE_OPEN);
        }
        for (Day day : hours.days()) {
            Day next = hours.day((day.dayOfWeek() + 1) % WeeklyHours.DAYS);
            if (day.closed() || next.closed()) {
                continue;
            }
            // Both measured in hours from the day's own midnight; the next day's opening
            // is a day later, so it gets 24 added to it.
            int closes = day.open().getHour() + day.lengthHours();
            int opens = WeeklyHours.HOURS_IN_DAY + next.open().getHour();
            if (opens - closes < MIN_GAP_HOURS) {
                throw new BadRequestException("There must be at least " + MIN_GAP_HOURS + " hour between "
                        + fullName(day.dayOfWeek()) + "'s closing and "
                        + fullName(next.dayOfWeek()) + "'s opening");
            }
        }
        return hours;
    }

    private static boolean onTheHour(LocalTime time) {
        return time.getMinute() == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }

    private static String fullName(int dayOfWeek) {
        return DayOfWeek.of(dayOfWeek + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String shortName(int dayOfWeek) {
        return DayOfWeek.of(dayOfWeek + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }
}
