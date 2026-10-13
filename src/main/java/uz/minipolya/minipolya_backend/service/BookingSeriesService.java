package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingSeriesRequest;
import uz.minipolya.minipolya_backend.dto.response.BookingSeriesResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.BookingSeries;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recurring bookings. A series generates pending bookings for a rolling 2-week
 * window; each generated booking uses the CURRENT price. Unavailable slots are
 * skipped, never retried. Reuses the existing {@code booking_series_id} column
 * on bookings (added in step 8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingSeriesService {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final String STATUS_APPROVED = "approved";
    private static final int WINDOW_WEEKS = 2;

    private final BookingSeriesRepository seriesRepository;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final PriceCalculator priceCalculator;
    private final BookingSeriesMapper seriesMapper;
    private final BookingService bookingService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AppProperties appProperties;

    @Transactional
    public BookingSeriesResponse createSeries(CreateBookingSeriesRequest request, Long userId) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(request.getStadiumId(), STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not available"));

        int duration = request.getDuration();
        LocalTime startTime = request.getStartTime();
        LocalTime endTime = startTime.plusHours(duration);
        boolean validWindow = !startTime.isBefore(stadium.getOpenTime())
                && endTime.isAfter(startTime) // no midnight wrap
                && !endTime.isAfter(stadium.getCloseTime());
        if (!validWindow) {
            throw new BadRequestException("Requested time is outside the stadium's operating hours");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        BookingSeries series = seriesRepository.save(BookingSeries.builder()
                .user(user)
                .stadium(stadium)
                .dayOfWeek(request.getDayOfWeek())
                .startTime(startTime)
                .durationHours(duration)
                .isActive(true)
                .build());

        List<LocalDate> occurrences = nextOccurrences(series.getDayOfWeek(), startTime, WINDOW_WEEKS);
        for (LocalDate date : occurrences) {
            tryGenerateBooking(series, date);
        }

        auditService.log(userId, "BOOKING_SERIES_CREATED", "booking_series", series.getId());
        log.info("Booking series created: id={} userId={} stadiumId={} day={} {}",
                series.getId(), userId, stadium.getId(), series.getDayOfWeek(), startTime);
        return toResponse(series, occurrences.isEmpty() ? null : occurrences.get(0));
    }

    @Transactional(readOnly = true)
    public List<BookingSeriesResponse> getMySeries(Long userId) {
        return seriesRepository.findByUserIdAndIsActiveTrue(userId).stream()
                .map(s -> toResponse(s, nextOccurrences(s.getDayOfWeek(), s.getStartTime(), 1).get(0)))
                .toList();
    }

    @Transactional
    public void cancelSeries(Long seriesId, Long userId) {
        BookingSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new ResourceNotFoundException("Series not found"));
        if (!series.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This series does not belong to you");
        }
        series.setActive(false);
        seriesRepository.save(series);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Booking booking : bookingRepository.findByBookingSeriesId(seriesId)) {
            boolean active = "pending".equals(booking.getStatus()) || "confirmed".equals(booking.getStatus());
            if (active && booking.getStartsAt().isAfter(now)) {
                bookingService.cancelByUser(userId, booking.getId(), "Recurring series cancelled");
            }
        }
        auditService.log(userId, "BOOKING_SERIES_CANCELLED", "booking_series", seriesId);
        log.info("Booking series cancelled: id={} userId={}", seriesId, userId);
    }

    /** Called daily by BookingSeriesJob to keep the rolling 2-week window filled. */
    @Transactional
    public void generateUpcomingBookings() {
        for (BookingSeries series : seriesRepository.findByIsActiveTrue()) {
            List<LocalDate> occurrences = nextOccurrences(series.getDayOfWeek(), series.getStartTime(), WINDOW_WEEKS);
            Set<LocalDate> existingDates = bookingRepository.findByBookingSeriesId(series.getId()).stream()
                    .map(b -> b.getStartsAt().atZoneSameInstant(TASHKENT).toLocalDate())
                    .collect(Collectors.toSet());

            for (LocalDate date : occurrences) {
                if (existingDates.contains(date)) {
                    continue; // idempotent: already generated for this date
                }
                boolean created = tryGenerateBooking(series, date);
                notifyGeneration(series, date, created);
            }
        }
    }

    // ---- Helpers ----

    /** @return true if a booking was created, false if the slot was taken. */
    private boolean tryGenerateBooking(BookingSeries series, LocalDate date) {
        Stadium stadium = series.getStadium();
        int duration = series.getDurationHours();
        OffsetDateTime startsAt = date.atTime(series.getStartTime()).atZone(TASHKENT).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(duration);

        if (bookingRepository.existsActiveOverlap(stadium.getId(), startsAt, endsAt)) {
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Booking booking = Booking.builder()
                .stadium(stadium)
                .user(series.getUser())
                .source("app")
                .status("pending")
                .startsAt(startsAt)
                .endsAt(endsAt)
                .durationHours(duration)
                .price(calculatePrice(stadium, startsAt, duration)) // CURRENT pricing
                .bookingSeriesId(series.getId())
                .expiresAt(now.plusMinutes(appProperties.getBooking().getHoldExpiryMinutes()))
                .build();
        try {
            bookingRepository.saveAndFlush(booking);
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false; // EXCLUDE caught a concurrent overlap
        }
    }

    /** The next {@code count} occurrences of the weekday, strictly in the future (Tashkent). */
    private List<LocalDate> nextOccurrences(int dayOfWeek, LocalTime startTime, int count) {
        ZonedDateTime now = ZonedDateTime.now(TASHKENT);
        LocalDate cursor = now.toLocalDate();
        int targetJava = dayOfWeek + 1; // app 0=Mon..6=Sun -> java 1=Mon..7=Sun
        int diff = (targetJava - cursor.getDayOfWeek().getValue() + 7) % 7;
        LocalDate first = cursor.plusDays(diff);
        if (diff == 0 && !startTime.isAfter(now.toLocalTime())) {
            first = first.plusWeeks(1); // today's slot already started
        }
        List<LocalDate> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(first.plusWeeks(i));
        }
        return result;
    }

    private void notifyGeneration(BookingSeries series, LocalDate date, boolean created) {
        Long userId = series.getUser().getId();
        String stadiumName = series.getStadium().getName();
        try {
            if (created) {
                notificationService.notify(userId, Notification.Type.BOOKING_REMINDER,
                        "Повторяющаяся бронь",
                        "Бронь в «" + stadiumName + "» на " + date + " создана. Оплатите в течение 10 минут.",
                        Notification.ReferenceType.BOOKING, series.getId());
            } else {
                notificationService.notify(userId, Notification.Type.BOOKING_CANCELLED,
                        "Повторяющаяся бронь",
                        "Бронь в «" + stadiumName + "» на " + date + " не создана — слот занят.",
                        Notification.ReferenceType.BOOKING, series.getId());
            }
        } catch (Exception ex) {
            log.warn("Series notification failed: {}", ex.toString());
        }
    }

    private int calculatePrice(Stadium stadium, OffsetDateTime startsAt, int duration) {
        ZonedDateTime local = startsAt.atZoneSameInstant(TASHKENT);
        return priceCalculator.calculateTotalPrice(
                pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadium.getId()),
                local.toLocalDate(),
                local.toLocalTime(),
                duration,
                stadium.getPricePerHour());
    }

    private BookingSeriesResponse toResponse(BookingSeries series, LocalDate nextBookingDate) {
        BookingSeriesResponse response = seriesMapper.toResponse(series);
        response.setNextBookingDate(nextBookingDate);
        return response;
    }
}
