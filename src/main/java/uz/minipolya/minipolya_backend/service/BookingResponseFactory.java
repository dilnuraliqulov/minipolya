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
    private final CardPricing cardPricing;

    /** The player's view: card prices, no netPrice. */
    public BookingResponse of(Booking booking) {
        return of(booking, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** The owner's and admin's view: the same, plus the owner's netPrice. */
    public BookingResponse forOwner(Booking booking) {
        BookingResponse response = of(booking);
        response.setNetPrice(booking.getPrice());
        return response;
    }

    public List<BookingResponse> forOwner(List<Booking> bookings) {
        return bookings.stream().map(this::forOwner).toList();
    }

    public BookingResponse of(Booking booking, OffsetDateTime now) {
        BookingResponse response = bookingMapper.toResponse(booking);
        applyCardPrices(response, booking);
        if (booking.getStartsAt() == null || booking.getStadium() == null) {
            return response; // not enough to say anything about cancelling
        }
        response.setCancellationDeadline(cancellationPolicy.deadline(booking));
        response.setRefundIfCancelledNow(cancellationPolicy.refundable(booking, now)
                ? paymentService.refundableCardTotal(booking.getId())
                : 0L);
        return response;
    }

    /**
     * {@code price} becomes the card price — stored at booking time, or worked out from
     * the net price for manual and older bookings — and the remainder is offered both ways.
     */
    private void applyCardPrices(BookingResponse response, Booking booking) {
        if (booking.getPrice() == null) {
            return;
        }
        long net = booking.getPrice();
        long card = booking.getCardPrice() != null ? booking.getCardPrice() : cardPricing.playerAmount(net);
        response.setPrice(card);
        response.setCardFee(card - net);
        long remainingNet = booking.getRemainingAmount();
        response.setRemainderInCash(remainingNet);
        response.setRemainderByCard(remainingNet > 0 ? cardPricing.playerAmount(remainingNet) : 0L);
    }

    public List<BookingResponse> of(List<Booking> bookings) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return bookings.stream().map(booking -> of(booking, now)).toList();
    }
}
