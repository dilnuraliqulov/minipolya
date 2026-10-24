package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerDashboardStatsResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerRevenueResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerRevenueResponse.DailyRevenue;
import uz.minipolya.minipolya_backend.dto.response.OwnerScheduleResponse;
import uz.minipolya.minipolya_backend.dto.response.ReviewResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only aggregation layer for the stadium owner's dashboard. Every method
 * verifies the caller owns the stadium. Date boundaries use Tashkent time;
 * revenue counts completed booking payments only.
 */
@Service
@RequiredArgsConstructor
public class OwnerDashboardService {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final List<String> OCCUPIED_STATUSES = List.of("pending", "confirmed", "completed");
    private static final List<String> COUNTED_STATUSES = List.of("confirmed", "completed");

    private final StadiumRepository stadiumRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final ReviewRepository reviewRepository;
    private final BookingMapper bookingMapper;
    private final StadiumService stadiumService;
    private final ReviewService reviewService;

    @Transactional(readOnly = true)
    public List<StadiumResponse> getMyStadiums(UUID ownerUserId) {
        return stadiumService.listOwnerStadiums(ownerUserId);
    }

    @Transactional(readOnly = true)
    public OwnerDashboardStatsResponse getStats(UUID stadiumId, UUID ownerUserId) {
        Stadium stadium = requireOwnedStadium(stadiumId, ownerUserId);
        LocalDate today = LocalDate.now(TASHKENT);

        OffsetDateTime todayStart = startOfDay(today);
        OffsetDateTime todayEnd = startOfDay(today.plusDays(1));
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime weekStartUtc = startOfDay(weekStart);
        OffsetDateTime weekEndUtc = startOfDay(weekStart.plusWeeks(1));
        OffsetDateTime monthStartUtc = startOfDay(today.withDayOfMonth(1));
        OffsetDateTime monthEndUtc = startOfDay(today.withDayOfMonth(1).plusMonths(1));

        int slotsPerDay = (int) Duration.between(stadium.getOpenTime(), stadium.getCloseTime()).toHours();
        int totalWeekSlots = slotsPerDay * 7;
        int bookedWeekSlots = bookingRepository.findByStadiumIdAndStartsAtBetween(stadiumId, weekStartUtc, weekEndUtc)
                .stream()
                .filter(b -> OCCUPIED_STATUSES.contains(b.getStatus()))
                .mapToInt(b -> b.getDurationHours() == null ? 0 : b.getDurationHours())
                .sum();
        int occupancy = totalWeekSlots > 0 ? Math.min(100, bookedWeekSlots * 100 / totalWeekSlots) : 0;

        Double avg = reviewRepository.averageRatingByStadiumId(stadiumId);

        return OwnerDashboardStatsResponse.builder()
                .stadiumId(stadiumId)
                .stadiumName(stadium.getName())
                .todayBookings((int) bookingRepository.countByStadiumAndDateRange(stadiumId, todayStart, todayEnd, COUNTED_STATUSES))
                .todayRevenue(revenue(stadiumId, todayStart, todayEnd))
                .weekBookings((int) bookingRepository.countByStadiumAndDateRange(stadiumId, weekStartUtc, weekEndUtc, COUNTED_STATUSES))
                .weekRevenue(revenue(stadiumId, weekStartUtc, weekEndUtc))
                .monthBookings((int) bookingRepository.countByStadiumAndDateRange(stadiumId, monthStartUtc, monthEndUtc, COUNTED_STATUSES))
                .monthRevenue(revenue(stadiumId, monthStartUtc, monthEndUtc))
                .totalBookings((int) bookingRepository.countByStadiumIdAndStatusIn(stadiumId, COUNTED_STATUSES))
                .totalRevenue(paymentRepository.sumRevenueByStadium(stadiumId, Payment.Status.COMPLETED, Payment.PaymentType.BOOKING))
                .averageRating(avg == null ? 0.0 : avg)
                .reviewCount(reviewRepository.countByStadiumId(stadiumId))
                .occupancyPercent(occupancy)
                .build();
    }

    @Transactional(readOnly = true)
    public OwnerRevenueResponse getRevenue(UUID stadiumId, UUID ownerUserId) {
        requireOwnedStadium(stadiumId, ownerUserId);
        LocalDate today = LocalDate.now(TASHKENT);
        OffsetDateTime from = startOfDay(today.minusDays(29));
        OffsetDateTime to = startOfDay(today.plusDays(1));

        Map<LocalDate, long[]> byDate = new LinkedHashMap<>(); // [revenue in tiyin, count]
        for (Payment payment : paymentRepository.findStadiumBookingPayments(
                stadiumId, Payment.Status.COMPLETED, Payment.PaymentType.BOOKING, from, to)) {
            LocalDate date = payment.getCreatedAt().atZoneSameInstant(TASHKENT).toLocalDate();
            long[] agg = byDate.computeIfAbsent(date, d -> new long[2]);
            agg[0] += payment.getAmount();
            agg[1] += 1;
        }

        List<DailyRevenue> daily = byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> DailyRevenue.builder()
                        .date(e.getKey()).revenue(e.getValue()[0]).bookingCount((int) e.getValue()[1]).build())
                .toList();

        return OwnerRevenueResponse.builder().stadiumId(stadiumId).dailyRevenue(daily).build();
    }

    @Transactional(readOnly = true)
    public List<OwnerScheduleResponse> getSchedule(UUID stadiumId, LocalDate from, LocalDate to, UUID ownerUserId) {
        Stadium stadium = requireOwnedStadium(stadiumId, ownerUserId);
        LocalDate today = LocalDate.now(TASHKENT);
        LocalDate rangeFrom = from != null ? from : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate rangeTo = to != null ? to : rangeFrom.plusDays(6);

        int slotsPerDay = (int) Duration.between(stadium.getOpenTime(), stadium.getCloseTime()).toHours();
        List<Booking> all = bookingRepository.findByStadiumIdAndStartsAtBetween(
                stadiumId, startOfDay(rangeFrom), startOfDay(rangeTo.plusDays(1)));

        Map<LocalDate, List<Booking>> byDate = new LinkedHashMap<>();
        for (Booking booking : all) {
            LocalDate date = booking.getStartsAt().atZoneSameInstant(TASHKENT).toLocalDate();
            byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(booking);
        }

        List<OwnerScheduleResponse> schedule = new ArrayList<>();
        for (LocalDate date = rangeFrom; !date.isAfter(rangeTo); date = date.plusDays(1)) {
            List<Booking> dayBookings = byDate.getOrDefault(date, List.of()).stream()
                    .sorted(Comparator.comparing(Booking::getStartsAt))
                    .toList();
            int bookedSlots = dayBookings.stream()
                    .filter(b -> OCCUPIED_STATUSES.contains(b.getStatus()))
                    .mapToInt(b -> b.getDurationHours() == null ? 0 : b.getDurationHours())
                    .sum();
            long revenue = dayBookings.stream()
                    .filter(b -> COUNTED_STATUSES.contains(b.getStatus()))
                    .mapToLong(b -> b.getPrice() == null ? 0 : b.getPrice())
                    .sum();
            schedule.add(OwnerScheduleResponse.builder()
                    .date(date)
                    .bookings(dayBookings.stream().map(bookingMapper::toResponse).toList())
                    .totalSlots(slotsPerDay)
                    .bookedSlots(bookedSlots)
                    .revenue(revenue)
                    .build());
        }
        return schedule;
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getStadiumReviews(UUID stadiumId, UUID ownerUserId, Pageable pageable) {
        requireOwnedStadium(stadiumId, ownerUserId);
        return reviewService.getStadiumReviews(stadiumId, pageable);
    }

    // ---- Helpers ----

    private long revenue(UUID stadiumId, OffsetDateTime from, OffsetDateTime to) {
        return paymentRepository.sumRevenueByStadiumAndDateRange(
                stadiumId, Payment.Status.COMPLETED, Payment.PaymentType.BOOKING, from, to);
    }

    private OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(TASHKENT).toOffsetDateTime();
    }

    private Stadium requireOwnedStadium(UUID stadiumId, UUID ownerUserId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        return stadium;
    }
}
