package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TashkentDaysTest {

    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
    }

    @Test
    void aDayRunsFromTashkentMidnightToTheNextOne() {
        TashkentDays.Range day = TashkentDays.range(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));

        // Tashkent is UTC+5 all year: 00:00 local is 19:00 UTC the day before.
        assertThat(day.from()).isEqualTo(utc("2026-10-31T19:00:00Z"));
        assertThat(day.to()).isEqualTo(utc("2026-11-01T19:00:00Z"));
    }

    @Test
    void theBoundaryBelongsToTheDayItStarts() {
        TashkentDays.Range day = TashkentDays.range(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));

        assertThat(day.contains(utc("2026-10-31T18:59:59Z"))).as("23:59:59 on Oct 31 local").isFalse();
        assertThat(day.contains(utc("2026-10-31T19:00:00Z"))).as("00:00 on Nov 1 local").isTrue();
        assertThat(day.contains(utc("2026-11-01T18:59:59.999Z"))).as("23:59:59.999 on Nov 1 local").isTrue();
        assertThat(day.contains(utc("2026-11-01T19:00:00Z"))).as("00:00 on Nov 2 local").isFalse();
    }

    @Test
    void bothDaysAreIncluded() {
        TashkentDays.Range range = TashkentDays.range(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

        assertThat(range.contains(utc("2026-11-03T18:00:00Z"))).isTrue();
        assertThat(range.contains(utc("2026-11-03T19:00:00Z"))).isFalse();
    }

    @Test
    void eitherEndMayBeOpen() {
        TashkentDays.Range from = TashkentDays.range(LocalDate.of(2026, 11, 1), null);
        TashkentDays.Range to = TashkentDays.range(null, LocalDate.of(2026, 11, 1));

        assertThat(from.to()).isNull();
        assertThat(to.from()).isNull();
        assertThat(from.contains(utc("2030-01-01T00:00:00Z"))).isTrue();
        assertThat(to.contains(utc("2000-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void aBackwardsRangeIs400() {
        assertThatThrownBy(() -> TashkentDays.range(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("to must be on or after from");
    }

    @Test
    void aRequiredRangeNeedsBothEnds() {
        assertThatThrownBy(() -> TashkentDays.requiredRange(null, LocalDate.of(2026, 11, 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("from and to are required");
    }

    @Test
    void todayIsTheTashkentDate() {
        // 20:30 UTC on Oct 31 is already 01:30 on Nov 1 in Tashkent.
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T20:30:00Z"), ZoneOffset.UTC);

        assertThat(TashkentDays.today(clock)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(TashkentDays.dateOf(utc("2026-10-31T20:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 1));
    }
}
