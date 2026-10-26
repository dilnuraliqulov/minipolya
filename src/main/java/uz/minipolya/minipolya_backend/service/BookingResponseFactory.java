package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Builds booking responses, adding the two cancellation fields the mapper
 * cannot work out on its own: the deadline and what a cancellation right now
 * would actually refund.
 *
 * <p>Both come from the same {@link CancellationPolicy} and the same refundable
 * payments that {@code PaymentService} gives back on a real cancellation, so a
 * player is never shown a figure the cancellation would not honour.
 */
@Component
@RequiredArgsConstructor
public class BookingResponseFactory {

    private final BookingMapper bookingMapper;
    private final CancellationPolicy cancellationPolicy;
    private final PaymentService paymentService;

    public BookingResponse of(Booking booking) {
        return of(booking, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public BookingResponse of(Booking booking, OffsetDateTime now) {
        BookingResponse response = bookingMapper.toResponse(booking);
        if (booking.getStartsAt() == null || booking.getStadium() == null) {
            return response; // not enough to say anything about cancelling
        }
        response.setCancellationDeadline(cancellationPolicy.deadline(booking));
        response.setRefundIfCancelledNow(cancellationPolicy.refundable(booking, now)
                ? paymentService.refundableCardTotal(booking.getId())
                : 0L);
        return response;
    }

    public List<BookingResponse> of(List<Booking> bookings) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return bookings.stream().map(booking -> of(booking, now)).toList();
    }
}
