package uz.minipolya.minipolya_backend.util;

import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Tashkent calendar days as UTC instants — the one place a {@code from}/{@code to} pair
 * of {@link LocalDate}s becomes a query range. A day runs from its 00:00 in
 * {@code Asia/Tashkent} up to, not including, the next day's 00:00, so
 * {@code [from 00:00, (to + 1 day) 00:00)} covers both days in full.
 */
public final class TashkentDays {

    public static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");

    private TashkentDays() {
    }

    /** A half-open range {@code [from, to)}; either end may be null for an open range. */
    public record Range(OffsetDateTime from, OffsetDateTime to) {

        public boolean contains(OffsetDateTime instant) {
            return (from == null || !instant.isBefore(from)) && (to == null || instant.isBefore(to));
        }
    }

    /** 00:00 Tashkent on this day, as an instant. */
    public static OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toOffsetDateTime();
    }

    /** Today's date in Tashkent. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** The Tashkent date an instant falls on. */
    public static LocalDate dateOf(OffsetDateTime instant) {
        return instant.atZoneSameInstant(ZONE).toLocalDate();
    }

    /**
     * Both days inclusive; either may be null. Throws 400 {@code "to must be on or after from"}
     * when the range is backwards.
     */
    public static Range range(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("to must be on or after from");
        }
        return new Range(from == null ? null : startOf(from), to == null ? null : startOf(to.plusDays(1)));
    }

    /** As {@link #range}, but both days are required: 400 {@code "from and to are required"}. */
    public static Range requiredRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BadRequestException("from and to are required");
        }
        return range(from, to);
    }
}
