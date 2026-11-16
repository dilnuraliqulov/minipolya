package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.util.WeeklyHours;
import uz.minipolya.minipolya_backend.util.WeeklyHours.Day;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * An hour must fall inside the stadium's weekly schedule, nights included.
 * 2026-11-20 is a Friday (dayOfWeek 4), 2026-11-21 a Saturday, 2026-11-22 a Sunday.
 */
@ExtendWith(MockitoExtension.class)
class BookingWindowValidatorTest {

    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 21);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 22);
    private static final int FRIDAY_DOW = 4;
    private static final int SATURDAY_DOW = 5;
    private static final int SUNDAY_DOW = 6;

    @Mock
    private StadiumClosureService closureService;

    private BookingWindowValidator validator;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        validator = new BookingWindowValidator(closureService);
        stadium = Stadium.builder().id(STADIUM_ID).build();
        lenient().when(closureService.isClosedAt(any(), any(), any())).thenReturn(false);
    }

    private static LocalTime at(int hour) {
        return LocalTime.of(hour, 0);
    }

    /** The whole week 08:00–00:00, with the given days replaced. */
    private void schedule(Day... overrides) {
        List<Day> days = new ArrayList<>();
        for (int dayOfWeek = 0; dayOfWeek < 7; dayOfWeek++) {
            days.add(Day.open(dayOfWeek, at(8), LocalTime.MIDNIGHT));
        }
        for (Day override : overrides) {
            days.set(override.dayOfWeek(), override);
        }
        stadium.setWeeklyHours(WeeklyHours.of(days));
    }

    private static OffsetDateTime tashkent(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(WeeklyHours.TASHKENT).toOffsetDateTime();
    }

    @Test
    void acceptsOneAmOnAFridayClosingAtTwo() {
        schedule(Day.open(FRIDAY_DOW, at(8), at(2)), Day.open(SATURDAY_DOW, at(10), LocalTime.MIDNIGHT));
        assertThatCode(() -> validator.validate(stadium, tashkent(SATURDAY, 1))).doesNotThrowAnyException();
    }

    @Test
    void acceptsElevenPmWhenClosingAtMidnight() {
        schedule();
        assertThatCode(() -> validator.validate(stadium, tashkent(FRIDAY, 23))).doesNotThrowAnyException();
    }

    @Test
    void refusesMidnightWhenClosingAtMidnight() {
        schedule();
        assertThatThrownBy(() -> validator.validate(stadium, tashkent(SATURDAY, 0)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(BookingWindowValidator.NOT_OPEN);
    }

    @Test
    void refusesTheClosingHourItself() {
        schedule(Day.open(FRIDAY_DOW, at(8), at(2)), Day.open(SATURDAY_DOW, at(10), LocalTime.MIDNIGHT));
        assertThatThrownBy(() -> validator.validate(stadium, tashkent(SATURDAY, 2)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(BookingWindowValidator.NOT_OPEN);
    }

    @Test
    void refusesAnHourOnAClosedDay() {
        schedule(Day.closed(SUNDAY_DOW));
        assertThatThrownBy(() -> validator.validate(stadium, tashkent(SUNDAY, 20)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(BookingWindowValidator.NOT_OPEN);
    }

    @Test
    void refusesAnOffTheHourStart() {
        schedule();
        OffsetDateTime halfPast = FRIDAY.atTime(20, 30).atZone(WeeklyHours.TASHKENT).toOffsetDateTime();
        assertThatThrownBy(() -> validator.validate(stadium, halfPast))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Booking must start on the hour");
    }

    @Test
    void theWorkingDateOfAOneAmSlotIsThePreviousDay() {
        schedule(Day.open(FRIDAY_DOW, at(8), at(2)), Day.open(SATURDAY_DOW, at(10), LocalTime.MIDNIGHT));
        assertThat(validator.workingDateOf(stadium, tashkent(SATURDAY, 1))).isEqualTo(FRIDAY);
        assertThat(validator.workingDateOf(stadium, tashkent(SATURDAY, 23))).isEqualTo(SATURDAY);
    }

    @Test
    void aClosureOnTheWorkingDayCoversTheNight() {
        schedule(Day.open(FRIDAY_DOW, at(8), at(2)), Day.open(SATURDAY_DOW, at(10), LocalTime.MIDNIGHT));
        // The closure is asked about Friday, the working day, not Saturday the slot falls on.
        when(closureService.isClosedAt(eq(STADIUM_ID), eq(FRIDAY), any())).thenReturn(true);
        assertThatThrownBy(() -> validator.validate(stadium, tashkent(SATURDAY, 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Stadium is closed on " + FRIDAY);
    }

    @Test
    void aStadiumWithNoScheduleRowsFallsBackToItsOwnHours() {
        // Review Focus 1: no stadium_hours rows at all.
        stadium.setOpenTime(at(8));
        stadium.setCloseTime(at(22));
        assertThatCode(() -> validator.validate(stadium, tashkent(FRIDAY, 20))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(stadium, tashkent(FRIDAY, 22)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(BookingWindowValidator.NOT_OPEN);
    }
}
