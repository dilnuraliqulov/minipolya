package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.AdminBookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.AdminActivityMapper;
import uz.minipolya.minipolya_backend.repository.AdminBookingFilter;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.BookingSpecifications;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.util.Pagination;
import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every booking on the platform, for the admin panel — read only. A page costs a fixed
 * handful of queries however many rows it holds: the bookings with their stadium and
 * user, the count, and all their payments in one go.
 */
@Service
@RequiredArgsConstructor
public class AdminBookingService {

    private static final Sort NEWEST_START_FIRST = Sort.by(Sort.Direction.DESC, "startsAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    /** Card money that arrived and was not given back. */
    private static final Set<Payment.Status> KEPT = Set.of(
            Payment.Status.PAID, Payment.Status.CAPTURED, Payment.Status.COMPLETED);

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingResponseFactory bookingResponseFactory;
    private final AdminActivityMapper adminActivityMapper;
    private final CardPricing cardPricing;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<AdminBookingResponse> list(String search, UUID stadiumId, String status,
                                           LocalDate from, LocalDate to, UUID userId, int page, int size) {
        TashkentDays.Range startsAt = TashkentDays.range(from, to);
        Pageable pageable = Pagination.of(page, size, NEWEST_START_FIRST);
        Page<Booking> bookings = bookingRepository.findAll(BookingSpecifications.adminFilter(
                new AdminBookingFilter(search, stadiumId, status, startsAt, userId)), pageable);

        List<UUID> ids = bookings.getContent().stream().map(Booking::getId).toList();
        Map<UUID, List<Payment>> payments = ids.isEmpty() ? Map.of()
                : paymentRepository.findByBookingIdIn(ids).stream()
                        .collect(Collectors.groupingBy(p -> p.getBooking().getId()));
        CardPricing pricing = cardPricing.snapshot();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return bookings.map(b -> toResponse(b, payments.getOrDefault(b.getId(), List.of()), pricing, now));
    }

    @Transactional(readOnly = true)
    public AdminBookingResponse get(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        return toResponse(booking, paymentRepository.findByBookingId(bookingId),
                cardPricing.snapshot(), OffsetDateTime.now(clock));
    }

    private AdminBookingResponse toResponse(Booking booking, List<Payment> payments,
                                            CardPricing pricing, OffsetDateTime now) {
        AdminBookingResponse response = adminActivityMapper.toAdminBooking(booking);
        response.setBooking(bookingResponseFactory.forOwner(booking, now, payments, pricing));
        response.setPaidInApp(payments.stream()
                .filter(p -> p.getMethod() == Payment.Method.CARD)
                .filter(p -> KEPT.contains(p.getStatus()))
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .mapToLong(Payment::getAmount)
                .sum());
        List<Payment> cash = payments.stream()
                .filter(p -> p.getMethod() == Payment.Method.CASH)
                .filter(p -> KEPT.contains(p.getStatus()))
                .toList();
        if (!cash.isEmpty()) {
            response.setCashReceived(cash.stream().mapToLong(Payment::getAmount).sum());
            response.setCashReceivedAt(cash.stream()
                    .map(Payment::getCreatedAt)
                    .max(Comparator.naturalOrder())
                    .orElse(null));
        }
        return response;
    }
}
