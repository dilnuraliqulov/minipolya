package uz.minipolya.minipolya_backend.util;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A stadium's weekly opening schedule, and <b>the</b> place the working-day rule is
 * expressed.
 *
 * <p>Each day of the week (0 = Monday) is either closed or open between two whole hours.
 * A closing time at or before the opening time means <i>the next morning</i>
 * (08:00–02:00 is a twenty-hour day), and a closing time of 00:00 means midnight — the
 * end of the same calendar day. A day is open at most 24 hours.
 *
 * <p>A <b>working day</b> is the day whose opening started the stretch. The hours after
 * midnight belong to it, not to the calendar day they fall on: with Saturday 08:00–02:00,
 * the hour starting Sunday 00:00 in Tashkent is <i>Saturday's</i> 00:00 slot. Bookings
 * still store the real instants, so everything built on instants — the EXCLUDE
 * constraint, payments, cancellation deadlines, the jobs — needs no special case. Only
 * the things that ask "which day is this?" go through here: availability, pricing rules,
 * promotions and closures.
 *
 * <p>Immutable; always exactly seven days, Monday first.
 */
public final class WeeklyHours {

    public static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    public static final int DAYS = 7;
    public static final int HOURS_IN_DAY = 24;

    private final List<Day> days;

    private WeeklyHours(List<Day> days) {
        this.days = List.copyOf(days);
    }

    /**
     * One day of the week. {@code dayOfWeek} is 0 = Monday … 6 = Sunday. A closed day
     * carries no times; an open day carries both.
     */
    public record Day(int dayOfWeek, boolean closed, LocalTime open, LocalTime close) {

        public Day {
            if (dayOfWeek < 0 || dayOfWeek >= DAYS) {
                throw new IllegalArgumentException("dayOfWeek must be 0..6, was " + dayOfWeek);
            }
            if (closed) {
                // A closed day has no hours, whatever a caller passed.
                open = null;
                close = null;
            }
        }

        public static Day closed(int dayOfWeek) {
            return new Day(dayOfWeek, true, null, null);
        }

        public static Day open(int dayOfWeek, LocalTime open, LocalTime close) {
            return new Day(dayOfWeek, false, open, close);
        }

        /**
         * How many whole hours the day is open: 0 when closed, {@code close - open} when
         * the closing time is later the same day, and the way round the clock otherwise
         * (00:00 counting as 24:00). Opening and closing at the same hour is a full 24.
         */
        public int lengthHours() {
            if (closed || open == null || close == null) {
                return 0;
            }
            if (close.equals(LocalTime.MIDNIGHT)) {
                return HOURS_IN_DAY - open.getHour();
            }
            if (close.isAfter(open)) {
                return close.getHour() - open.getHour();
            }
            return HOURS_IN_DAY - open.getHour() + close.getHour();
        }

        /** Whether the closing moment falls on the next calendar date (00:00 included). */
        public boolean endsNextDay() {
            if (closed || open == null || close == null) {
                return false;
            }
            return close.equals(LocalTime.MIDNIGHT) || !close.isAfter(open);
        }

        /** Whether the day has bookable hours after midnight (00:00 closing has none). */
        public boolean hasHoursAfterMidnight() {
            return endsNextDay() && !close.equals(LocalTime.MIDNIGHT);
        }

        /**
         * Whether the one-hour slot at this clock time is part of the day at all, whether
         * it falls before or after midnight.
         */
        public boolean containsSlot(LocalTime time) {
            return containsHour(time, 0) || containsHour(time, 1);
        }

        /**
         * Whether the one-hour slot at this clock time falls on the calendar day after the
         * opening — the hours a day running past midnight reaches into the next morning.
         */
        public boolean isAfterMidnight(LocalTime time) {
            return !containsHour(time, 0) && containsHour(time, 1);
        }

        /**
         * Whether the one-hour slot starting at {@code time} belongs to this day, when
         * {@code time} is read on the opening date ({@code dayOffset} 0) or on the
         * morning after it ({@code dayOffset} 1).
         */
        boolean containsHour(LocalTime time, int dayOffset) {
            if (closed || open == null) {
                return false;
            }
            int hoursFromOpening = dayOffset * HOURS_IN_DAY + time.getHour() - open.getHour();
            if (dayOffset == 0 && time.isBefore(open)) {
                return false;
            }
            return hoursFromOpening >= 0 && hoursFromOpening < lengthHours();
        }
    }

    /** The seven days in any order; exactly one row per day of the week. */
    public static WeeklyHours of(List<Day> days) {
        if (days == null || days.size() != DAYS) {
            throw new IllegalArgumentException("A weekly schedule needs all " + DAYS + " days, got "
                    + (days == null ? "none" : days.size()));
        }
        Set<Integer> seen = new HashSet<>();
        for (Day day : days) {
            if (!seen.add(day.dayOfWeek())) {
                throw new IllegalArgumentException("Duplicate day of week: " + day.dayOfWeek());
            }
        }
        List<Day> sorted = new ArrayList<>(days);
        sorted.sort(Comparator.comparingInt(Day::dayOfWeek));
        return new WeeklyHours(sorted);
    }

    /** The same window every day — what a stadium with one pair of hours means. */
    public static WeeklyHours sameEveryDay(LocalTime open, LocalTime close) {
        List<Day> days = new ArrayList<>(DAYS);
        for (int dayOfWeek = 0; dayOfWeek < DAYS; dayOfWeek++) {
            days.add(Day.open(dayOfWeek, open, close));
        }
        return new WeeklyHours(days);
    }

    /** Always seven days, Monday first. */
    public List<Day> days() {
        return days;
    }

    public Day day(int dayOfWeek) {
        if (dayOfWeek < 0 || dayOfWeek >= DAYS) {
            throw new IllegalArgumentException("dayOfWeek must be 0..6, was " + dayOfWeek);
        }
        return days.get(dayOfWeek);
    }

    /** The schedule of the working day {@code workingDate} is. */
    public Day dayOf(LocalDate workingDate) {
        return day(dayOfWeekOf(workingDate));
    }

    /** The sum of the week's open hours — what a full week's slots add up to. */
    public int weekHours() {
        return days.stream().mapToInt(Day::lengthHours).sum();
    }

    /** Whether every day of the week is closed. */
    public boolean allClosed() {
        return days.stream().allMatch(Day::closed);
    }

    /**
     * Which working day opened the one-hour slot starting at {@code slotStart}: its own
     * Tashkent date when that day is open then, else the date before when that day runs
     * past midnight into this hour. Empty when the stadium is not open at that hour.
     */
    public Optional<LocalDate> workingDate(ZonedDateTime slotStart) {
        ZonedDateTime local = slotStart.withZoneSameInstant(TASHKENT);
        LocalDate date = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        if (dayOf(date).containsHour(time, 0)) {
            return Optional.of(date);
        }
        LocalDate previous = date.minusDays(1);
        if (dayOf(previous).containsHour(time, 1)) {
            return Optional.of(previous);
        }
        return Optional.empty();
    }

    /** The app's convention for a date's weekday: 0 = Monday … 6 = Sunday. */
    public static int dayOfWeekOf(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }
}
