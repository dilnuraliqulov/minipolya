package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerDashboardStatsResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerRevenueResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerScheduleResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnerDashboardServiceTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    private StadiumRepository stadiumRepository;
    private BookingRepository bookingRepository;
    private PaymentRepository paymentRepository;
    private ReviewRepository reviewRepository;
    private BookingMapper bookingMapper;
    private StadiumService stadiumService;
    private ReviewService reviewService;
    private OwnerDashboardService service;

    private Stadium stadium;

    @BeforeEach
    void setUp() {
        stadiumRepository = mock(StadiumRepository.class);
        bookingRepository = mock(BookingRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        bookingMapper = mock(BookingMapper.class);
        stadiumService = mock(StadiumService.class);
        reviewService = mock(ReviewService.class);

        service = new OwnerDashboardService(stadiumRepository, bookingRepository, paymentRepository,
                reviewRepository, bookingMapper, stadiumService, reviewService);

        stadium = Stadium.builder().id(STADIUM_ID).name("Arena").owner(User.builder().id(OWNER_ID).build())
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0)).build();
    }

    @Test
    void getStatsReturnsCountsAndRevenue() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(bookingRepository.countByStadiumAndDateRange(eq(STADIUM_ID), any(), any(), any())).thenReturn(3L);
        when(bookingRepository.countByStadiumIdAndStatusIn(eq(STADIUM_ID), any())).thenReturn(50L);
        when(paymentRepository.sumRevenueByStadiumAndDateRange(eq(STADIUM_ID), any(), any(), any(), any())).thenReturn(100_000L);
        when(paymentRepository.sumRevenueByStadium(eq(STADIUM_ID), any(), any())).thenReturn(5_000_000L);
        when(reviewRepository.averageRatingByStadiumId(STADIUM_ID)).thenReturn(4.5);
        when(reviewRepository.countByStadiumId(STADIUM_ID)).thenReturn(10);
        when(bookingRepository.findByStadiumIdAndStartsAtBetween(eq(STADIUM_ID), any(), any())).thenReturn(List.of());

        OwnerDashboardStatsResponse stats = service.getStats(STADIUM_ID, OWNER_ID);

        assertThat(stats.getStadiumName()).isEqualTo("Arena");
        assertThat(stats.getTodayBookings()).isEqualTo(3);
        assertThat(stats.getTodayRevenue()).isEqualTo(100_000);
        assertThat(stats.getTotalBookings()).isEqualTo(50);
        assertThat(stats.getTotalRevenue()).isEqualTo(5_000_000);
        assertThat(stats.getAverageRating()).isEqualTo(4.5);
        assertThat(stats.getReviewCount()).isEqualTo(10);
        assertThat(stats.getOccupancyPercent()).isZero();
    }

    @Test
    void getStatsRejectsNonOwner() {
        UUID nonOwnerId = UUID.randomUUID();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));

        assertThatThrownBy(() -> service.getStats(STADIUM_ID, nonOwnerId)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void occupancyIsBookedSlotsOverAvailable() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(bookingRepository.countByStadiumAndDateRange(any(UUID.class), any(), any(), any())).thenReturn(0L);
        when(bookingRepository.countByStadiumIdAndStatusIn(any(UUID.class), any())).thenReturn(0L);
        when(paymentRepository.sumRevenueByStadiumAndDateRange(any(UUID.class), any(), any(), any(), any())).thenReturn(0L);
        when(paymentRepository.sumRevenueByStadium(any(UUID.class), any(), any())).thenReturn(0L);
        when(reviewRepository.countByStadiumId(STADIUM_ID)).thenReturn(0);
        // 15 slots/day * 7 = 105; booked = three 2h + one 1h + ... total 21 hours -> 20%
        List<Booking> week = List.of(
                Booking.builder().status("confirmed").durationHours(2).startsAt(OffsetDateTime.now()).build(),
                Booking.builder().status("confirmed").durationHours(2).startsAt(OffsetDateTime.now()).build(),
                Booking.builder().status("completed").durationHours(2).startsAt(OffsetDateTime.now()).build(),
                Booking.builder().status("confirmed").durationHours(1).startsAt(OffsetDateTime.now()).build(),
                Booking.builder().status("pending").durationHours(2).startsAt(OffsetDateTime.now()).build(),
                Booking.builder().status("confirmed").durationHours(2).startsAt(OffsetDateTime.now()).build(),
                Booking.builder().status("completed").durationHours(2).startsAt(OffsetDateTime.now()).build(),
                Booking.builder().status("cancelled_by_user").durationHours(2).startsAt(OffsetDateTime.now()).build());
        when(bookingRepository.findByStadiumIdAndStartsAtBetween(eq(STADIUM_ID), any(), any())).thenReturn(week);

        OwnerDashboardStatsResponse stats = service.getStats(STADIUM_ID, OWNER_ID);

        // occupied statuses only: 2+2+2+1+2+2+2 = 13 -> 13*100/105 = 12
        assertThat(stats.getOccupancyPercent()).isEqualTo(12);
    }

    @Test
    void getRevenueGroupsByDay() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        OffsetDateTime d1 = LocalDate.now(TASHKENT).minusDays(1).atTime(12, 0).atZone(TASHKENT).toOffsetDateTime();
        OffsetDateTime d2 = LocalDate.now(TASHKENT).atTime(9, 0).atZone(TASHKENT).toOffsetDateTime();
        when(paymentRepository.findStadiumBookingPayments(eq(STADIUM_ID), any(), any(), any(), any())).thenReturn(List.of(
                Payment.builder().amount(100_000L).createdAt(d1).build(),
                Payment.builder().amount(50_000L).createdAt(d1).build(),
                Payment.builder().amount(70_000L).createdAt(d2).build()));

        OwnerRevenueResponse revenue = service.getRevenue(STADIUM_ID, OWNER_ID);

        assertThat(revenue.getDailyRevenue()).hasSize(2);
        assertThat(revenue.getDailyRevenue().get(0).getRevenue()).isEqualTo(150_000);
        assertThat(revenue.getDailyRevenue().get(0).getBookingCount()).isEqualTo(2);
        assertThat(revenue.getDailyRevenue().get(1).getRevenue()).isEqualTo(70_000);
    }

    @Test
    void getScheduleGroupsBookingsByDay() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        LocalDate today = LocalDate.now(TASHKENT);
        Booking later = Booking.builder().status("confirmed").durationHours(1).price(80_000L)
                .startsAt(today.atTime(10, 0).atZone(TASHKENT).toOffsetDateTime()).build();
        Booking earlier = Booking.builder().status("confirmed").durationHours(1).price(80_000L)
                .startsAt(today.atTime(9, 0).atZone(TASHKENT).toOffsetDateTime()).build();
        when(bookingRepository.findByStadiumIdAndStartsAtBetween(eq(STADIUM_ID), any(), any()))
                .thenReturn(List.of(later, earlier));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        List<OwnerScheduleResponse> schedule = service.getSchedule(STADIUM_ID, null, null, OWNER_ID);

        assertThat(schedule).hasSize(7); // default this week, Mon..Sun
        OwnerScheduleResponse todaySchedule = schedule.stream()
                .filter(s -> s.getDate().equals(today)).findFirst().orElseThrow();
        assertThat(todaySchedule.getBookings()).hasSize(2);
        assertThat(todaySchedule.getBookedSlots()).isEqualTo(2);
        assertThat(todaySchedule.getRevenue()).isEqualTo(160_000);
        assertThat(todaySchedule.getTotalSlots()).isEqualTo(15);
    }

    @Test
    void statsTotalRevenueAboveIntRangeIsNotTruncated() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(bookingRepository.countByStadiumAndDateRange(any(UUID.class), any(), any(), any())).thenReturn(0L);
        when(bookingRepository.countByStadiumIdAndStatusIn(any(UUID.class), any())).thenReturn(0L);
        when(paymentRepository.sumRevenueByStadiumAndDateRange(any(UUID.class), any(), any(), any(), any()))
                .thenReturn(0L);
        when(paymentRepository.sumRevenueByStadium(eq(STADIUM_ID), any(), any())).thenReturn(3_000_000_000L);
        when(reviewRepository.countByStadiumId(STADIUM_ID)).thenReturn(0);
        when(bookingRepository.findByStadiumIdAndStartsAtBetween(eq(STADIUM_ID), any(), any())).thenReturn(List.of());

        OwnerDashboardStatsResponse stats = service.getStats(STADIUM_ID, OWNER_ID);

        assertThat(stats.getTotalRevenue()).isEqualTo(3_000_000_000L);
    }

    @Test
    void dailyRevenueAboveIntRangeIsNotTruncated() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        OffsetDateTime day = LocalDate.now(TASHKENT).atTime(12, 0).atZone(TASHKENT).toOffsetDateTime();
        when(paymentRepository.findStadiumBookingPayments(eq(STADIUM_ID), any(), any(), any(), any()))
                .thenReturn(List.of(
                        Payment.builder().amount(2_000_000_000L).createdAt(day).build(),
                        Payment.builder().amount(2_000_000_000L).createdAt(day).build()));

        OwnerRevenueResponse revenue = service.getRevenue(STADIUM_ID, OWNER_ID);

        assertThat(revenue.getDailyRevenue().get(0).getRevenue()).isEqualTo(4_000_000_000L);
    }

    @Test
    void scheduleRevenueAboveIntRangeIsNotTruncated() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        LocalDate today = LocalDate.now(TASHKENT);
        Booking first = Booking.builder().status("confirmed").durationHours(1).price(2_000_000_000L)
                .startsAt(today.atTime(9, 0).atZone(TASHKENT).toOffsetDateTime()).build();
        Booking second = Booking.builder().status("confirmed").durationHours(1).price(2_000_000_000L)
                .startsAt(today.atTime(10, 0).atZone(TASHKENT).toOffsetDateTime()).build();
        when(bookingRepository.findByStadiumIdAndStartsAtBetween(eq(STADIUM_ID), any(), any()))
                .thenReturn(List.of(first, second));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        OwnerScheduleResponse todaySchedule = service.getSchedule(STADIUM_ID, null, null, OWNER_ID).stream()
                .filter(s -> s.getDate().equals(today)).findFirst().orElseThrow();

        assertThat(todaySchedule.getRevenue()).isEqualTo(4_000_000_000L);
    }

    @Test
    void getMyStadiumsDelegatesToStadiumService() {
        when(stadiumService.listOwnerStadiums(OWNER_ID)).thenReturn(List.of(new StadiumResponse()));

        assertThat(service.getMyStadiums(OWNER_ID)).hasSize(1);
    }
}
