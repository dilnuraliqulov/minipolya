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
import uz.minipolya.minipolya_backend.mapper.BookingSeriesMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.BookingSeriesRepository;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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

    private BookingSeriesRepository seriesRepository;
    private StadiumRepository stadiumRepository;
    private UserRepository userRepository;
    private BookingRepository bookingRepository;
    private PricingRuleRepository pricingRuleRepository;
    private PriceCalculator priceCalculator;
    private BookingSeriesMapper seriesMapper;
    private BookingService bookingService;
    private NotificationService notificationService;
    private AuditService auditService;
    private BookingSeriesService service;

    private User user;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        seriesRepository = mock(BookingSeriesRepository.class);
        stadiumRepository = mock(StadiumRepository.class);
        userRepository = mock(UserRepository.class);
        bookingRepository = mock(BookingRepository.class);
        pricingRuleRepository = mock(PricingRuleRepository.class);
        priceCalculator = mock(PriceCalculator.class);
        seriesMapper = mock(BookingSeriesMapper.class);
        bookingService = mock(BookingService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);

        service = new BookingSeriesService(seriesRepository, stadiumRepository, userRepository, bookingRepository,
                pricingRuleRepository, priceCalculator, seriesMapper, bookingService, notificationService,
                auditService, new AppProperties());

        user = User.builder().id(7L).name("Player").build();
        stadium = Stadium.builder().id(1L).name("Arena").status("approved").isActive(true)
                .pricePerHour(50_000).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0)).build();
    }

    private CreateBookingSeriesRequest request() {
        return CreateBookingSeriesRequest.builder()
                .stadiumId(1L).dayOfWeek(DAY).startTime(START).duration(1).build();
    }

    private BookingSeries series() {
        return BookingSeries.builder().id(10L).user(user).stadium(stadium)
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
        when(stadiumRepository.findByIdAndStatus(1L, "approved")).thenReturn(java.util.Optional.of(stadium));
        when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(user));
        when(seriesRepository.save(any(BookingSeries.class))).thenAnswer(inv -> {
            BookingSeries s = inv.getArgument(0);
            s.setId(10L);
            return s;
        });
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(bookingRepository.existsActiveOverlap(eq(1L), any(), any())).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seriesMapper.toResponse(any())).thenReturn(new BookingSeriesResponse());

        service.createSeries(request(), 7L);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, times(2)).saveAndFlush(captor.capture());
        Booking first = captor.getAllValues().get(0);
        assertThat(first.getStatus()).isEqualTo("pending");
        assertThat(first.getSource()).isEqualTo("app");
        assertThat(first.getBookingSeriesId()).isEqualTo(10L);
        assertThat(first.getExpiresAt()).isNotNull();
    }

    @Test
    void createSeriesSkipsTakenSlot() {
        when(stadiumRepository.findByIdAndStatus(1L, "approved")).thenReturn(java.util.Optional.of(stadium));
        when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(user));
        when(seriesRepository.save(any(BookingSeries.class))).thenAnswer(inv -> {
            BookingSeries s = inv.getArgument(0);
            s.setId(10L);
            return s;
        });
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(bookingRepository.existsActiveOverlap(eq(1L), any(), any())).thenReturn(true, false); // 1st taken, 2nd free
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seriesMapper.toResponse(any())).thenReturn(new BookingSeriesResponse());

        service.createSeries(request(), 7L);

        verify(bookingRepository, times(1)).saveAndFlush(any(Booking.class));
    }

    @Test
    void createSeriesUsesFreshPricing() {
        when(stadiumRepository.findByIdAndStatus(1L, "approved")).thenReturn(java.util.Optional.of(stadium));
        when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(user));
        when(seriesRepository.save(any(BookingSeries.class))).thenAnswer(inv -> inv.getArgument(0));
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(123_456);
        when(bookingRepository.existsActiveOverlap(eq(1L), any(), any())).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seriesMapper.toResponse(any())).thenReturn(new BookingSeriesResponse());

        service.createSeries(request(), 7L);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues().get(0).getPrice()).isEqualTo(123_456);
    }

    @Test
    void cancelSeriesDeactivatesAndCancelsFutureBookings() {
        BookingSeries series = series();
        Booking future = Booking.builder().id(5L).status("pending").user(user)
                .startsAt(OffsetDateTime.now().plusDays(3)).build();
        when(seriesRepository.findById(10L)).thenReturn(java.util.Optional.of(series));
        when(bookingRepository.findByBookingSeriesId(10L)).thenReturn(List.of(future));

        service.cancelSeries(10L, 7L);

        assertThat(series.isActive()).isFalse();
        verify(seriesRepository).save(series);
        verify(bookingService).cancelByUser(eq(7L), eq(5L), any());
    }

    @Test
    void cancelSeriesRefundsConfirmedFutureBookings() {
        BookingSeries series = series();
        Booking confirmed = Booking.builder().id(6L).status("confirmed").user(user)
                .startsAt(OffsetDateTime.now().plusDays(3)).build();
        when(seriesRepository.findById(10L)).thenReturn(java.util.Optional.of(series));
        when(bookingRepository.findByBookingSeriesId(10L)).thenReturn(List.of(confirmed));

        service.cancelSeries(10L, 7L);

        // cancelByUser applies the refund policy for confirmed bookings
        verify(bookingService).cancelByUser(eq(7L), eq(6L), any());
    }

    @Test
    void generateUpcomingCreatesBookingForMissingWeek() {
        when(seriesRepository.findByIsActiveTrue()).thenReturn(List.of(series()));
        when(bookingRepository.findByBookingSeriesId(10L)).thenReturn(List.of()); // nothing generated yet
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(bookingRepository.existsActiveOverlap(eq(1L), any(), any())).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generateUpcomingBookings();

        verify(bookingRepository, times(2)).saveAndFlush(any(Booking.class));
    }

    @Test
    void generateUpcomingIsIdempotent() {
        List<LocalDate> dates = occurrences(2);
        List<Booking> existing = dates.stream()
                .map(d -> Booking.builder().id(1L).bookingSeriesId(10L)
                        .startsAt(d.atTime(START).atZone(TASHKENT).toOffsetDateTime()).build())
                .toList();
        when(seriesRepository.findByIsActiveTrue()).thenReturn(List.of(series()));
        when(bookingRepository.findByBookingSeriesId(10L)).thenReturn(existing);

        service.generateUpcomingBookings();

        verify(bookingRepository, never()).saveAndFlush(any());
    }
}
