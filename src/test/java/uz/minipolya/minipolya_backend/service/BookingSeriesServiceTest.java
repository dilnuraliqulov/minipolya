package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingSeriesRequest;
import uz.minipolya.minipolya_backend.dto.response.BookingSeriesResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.BookingSeries;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.BookingSeriesMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.BookingSeriesRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingSeriesServiceTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final int DAY = 2;              // Wednesday (0=Mon)
    private static final LocalTime START = LocalTime.of(20, 0);

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID SERIES_ID = UUID.randomUUID();
    private static final UUID BOOKING_FUTURE_ID = UUID.randomUUID();
    private static final UUID BOOKING_CONFIRMED_ID = UUID.randomUUID();
    private static final UUID BOOKING_EXISTING_ID = UUID.randomUUID();

    private BookingSeriesRepository seriesRepository;
    private StadiumRepository stadiumRepository;
    private UserRepository userRepository;
    private BookingRepository bookingRepository;
    private BookingPriceCalculator bookingPriceCalculator;
    private BookingSeriesMapper seriesMapper;
    private BookingService bookingService;
    private NotificationService notificationService;
    private AuditService auditService;
    private StadiumClosureService closureService;
    private BookingSeriesService service;

    private User user;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        seriesRepository = mock(BookingSeriesRepository.class);
        stadiumRepository = mock(StadiumRepository.class);
        userRepository = mock(UserRepository.class);
        bookingRepository = mock(BookingRepository.class);
        bookingPriceCalculator = mock(BookingPriceCalculator.class);
        seriesMapper = mock(BookingSeriesMapper.class);
        bookingService = mock(BookingService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        closureService = mock(StadiumClosureService.class);

        service = new BookingSeriesService(seriesRepository, stadiumRepository, userRepository, bookingRepository,
                bookingPriceCalculator, seriesMapper, bookingService, notificationService,
                auditService, new AppProperties(), closureService);

        user = User.builder().id(USER_ID).name("Player").build();
        owner = User.builder().id(OWNER_ID).name("Owner").build();
        stadium = Stadium.builder().id(STADIUM_ID).name("Arena").status("approved").isActive(true)
                .pricePerHour(50_000L).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .owner(owner).build();
    }

    private CreateBookingSeriesRequest request() {
        return CreateBookingSeriesRequest.builder()
                .stadiumId(STADIUM_ID).dayOfWeek(DAY).startTime(START).duration(1).build();
    }

    private BookingSeries series() {
        return BookingSeries.builder().id(SERIES_ID).user(user).stadium(stadium)
                .dayOfWeek(DAY).startTime(START).durationHours(1).isActive(true).build();
    }

    /** Mirrors BookingSeriesService.nextOccurrences for building matching fixtures. */
    private List<LocalDate> occurrences(int count) {
        ZonedDateTime now = ZonedDateTime.now(TASHKENT);
        LocalDate cursor = now.toLocalDate();
        int diff = (DAY + 1 - cursor.getDayOfWeek().getValue() + 7) % 7;
        LocalDate first = cursor.plusDays(diff);
        if (diff == 0 && !START.isAfter(now.toLocalTime())) {
            first = first.plusWeeks(1);
        }
        List<LocalDate> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(first.plusWeeks(i));
        }
        return result;
    }

    @Test
    void createSeriesGeneratesTwoBookings() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(java.util.Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(java.util.Optional.of(user));
        when(seriesRepository.save(any(BookingSeries.class))).thenAnswer(inv -> {
            BookingSeries s = inv.getArgument(0);
            s.setId(SERIES_ID);
            return s;
        });
        when(bookingPriceCalculator.totalPrice(any(), any(), anyInt())).thenReturn(100_000L);
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seriesMapper.toResponse(any())).thenReturn(new BookingSeriesResponse());

        service.createSeries(request(), USER_ID);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, times(2)).saveAndFlush(captor.capture());
        Booking first = captor.getAllValues().get(0);
        assertThat(first.getStatus()).isEqualTo("pending");
        assertThat(first.getSource()).isEqualTo("app");
        assertThat(first.getBookingSeriesId()).isEqualTo(SERIES_ID);
        assertThat(first.getExpiresAt()).isNotNull();
    }

    @Test
    void createSeriesRejectsTheStadiumOwnerBookingTheirOwnPitch() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(java.util.Optional.of(stadium));

        assertThatThrownBy(() -> service.createSeries(request(), OWNER_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("manual booking");

        verify(seriesRepository, never()).save(any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createSeriesRejectsDurationOutsideOpeningHours() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));

        // 22:00 + 3h would run to 01:00, past the 23:00 close.
        CreateBookingSeriesRequest req = CreateBookingSeriesRequest.builder()
                .stadiumId(STADIUM_ID).dayOfWeek(DAY).startTime(LocalTime.of(22, 0)).duration(3).build();

        assertThatThrownBy(() -> service.createSeries(req, USER_ID))
                .isInstanceOf(BadRequestException.class);
        verify(seriesRepository, never()).save(any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createSeriesSkipsOccurrenceWithinClosure() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(seriesRepository.save(any(BookingSeries.class))).thenAnswer(inv -> {
            BookingSeries s = inv.getArgument(0);
            s.setId(SERIES_ID);
            return s;
        });
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(false);
        // Every generated occurrence is closed -> none produce a booking.
        when(closureService.isClosedAt(eq(STADIUM_ID), any(), any())).thenReturn(true);
        when(seriesMapper.toResponse(any())).thenReturn(new BookingSeriesResponse());

        service.createSeries(request(), USER_ID);

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createSeriesSkipsTakenSlot() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(java.util.Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(java.util.Optional.of(user));
        when(seriesRepository.save(any(BookingSeries.class))).thenAnswer(inv -> {
            BookingSeries s = inv.getArgument(0);
            s.setId(SERIES_ID);
            return s;
        });
        when(bookingPriceCalculator.totalPrice(any(), any(), anyInt())).thenReturn(100_000L);
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(true, false); // 1st taken, 2nd free
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seriesMapper.toResponse(any())).thenReturn(new BookingSeriesResponse());

        service.createSeries(request(), USER_ID);

        verify(bookingRepository, times(1)).saveAndFlush(any(Booking.class));
    }

    @Test
    void createSeriesUsesFreshPricing() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(java.util.Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(java.util.Optional.of(user));
        when(seriesRepository.save(any(BookingSeries.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingPriceCalculator.totalPrice(any(), any(), anyInt())).thenReturn(123_456L);
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seriesMapper.toResponse(any())).thenReturn(new BookingSeriesResponse());

        service.createSeries(request(), USER_ID);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues().get(0).getPrice()).isEqualTo(123_456);
    }

    @Test
    void cancelSeriesDeactivatesAndCancelsFutureBookings() {
        BookingSeries series = series();
        Booking future = Booking.builder().id(BOOKING_FUTURE_ID).status("pending").user(user)
                .startsAt(OffsetDateTime.now().plusDays(3)).build();
        when(seriesRepository.findById(SERIES_ID)).thenReturn(java.util.Optional.of(series));
        when(bookingRepository.findByBookingSeriesId(SERIES_ID)).thenReturn(List.of(future));

        service.cancelSeries(SERIES_ID, USER_ID);

        assertThat(series.isActive()).isFalse();
        verify(seriesRepository).save(series);
        verify(bookingService).cancelByUser(eq(USER_ID), eq(BOOKING_FUTURE_ID), any());
    }

    @Test
    void cancelSeriesRefundsConfirmedFutureBookings() {
        BookingSeries series = series();
        Booking confirmed = Booking.builder().id(BOOKING_CONFIRMED_ID).status("confirmed").user(user)
                .startsAt(OffsetDateTime.now().plusDays(3)).build();
        when(seriesRepository.findById(SERIES_ID)).thenReturn(java.util.Optional.of(series));
        when(bookingRepository.findByBookingSeriesId(SERIES_ID)).thenReturn(List.of(confirmed));

        service.cancelSeries(SERIES_ID, USER_ID);

        // cancelByUser applies the refund policy for confirmed bookings
        verify(bookingService).cancelByUser(eq(USER_ID), eq(BOOKING_CONFIRMED_ID), any());
    }

    @Test
    void generateUpcomingCreatesBookingForMissingWeek() {
        when(seriesRepository.findByIsActiveTrue()).thenReturn(List.of(series()));
        when(bookingRepository.findByBookingSeriesId(SERIES_ID)).thenReturn(List.of()); // nothing generated yet
        when(bookingPriceCalculator.totalPrice(any(), any(), anyInt())).thenReturn(100_000L);
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generateUpcomingBookings();

        verify(bookingRepository, times(2)).saveAndFlush(any(Booking.class));
    }

    @Test
    void generateUpcomingIsIdempotent() {
        List<LocalDate> dates = occurrences(2);
        List<Booking> existing = dates.stream()
                .map(d -> Booking.builder().id(BOOKING_EXISTING_ID).bookingSeriesId(SERIES_ID)
                        .startsAt(d.atTime(START).atZone(TASHKENT).toOffsetDateTime()).build())
                .toList();
        when(seriesRepository.findByIsActiveTrue()).thenReturn(List.of(series()));
        when(bookingRepository.findByBookingSeriesId(SERIES_ID)).thenReturn(existing);

        service.generateUpcomingBookings();

        verify(bookingRepository, never()).saveAndFlush(any());
    }
}
