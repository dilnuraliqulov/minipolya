package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Builds booking responses in three views.
 *
 * <ul>
 *   <li><b>Player</b> ({@link #of}): one price. {@code price} is the player price D,
 *       {@code paidAmount} what was paid of it, {@code remainingAmount} what is left — the
 *       same amount in the app or in cash. No owner price, fee or other breakdown.</li>
 *   <li><b>Owner</b> ({@link #forOwner}): the same {@code price}, {@code paidAmount} and
 *       {@code remainingAmount} as the player sees (the remainder to collect in cash is the
 *       remainder the player would pay in the app), plus {@code ownerPrice},
 *       {@code paidInApp} and {@code ownerReceives}.</li>
 *   <li><b>Admin</b> ({@link #forAdmin}): the owner's view plus {@code atmosFee}.</li>
 * </ul>
 *
 * <p>In every view {@code paidAmount + remainingAmount = price}, for every booking: one made
 * before the player price (and every game booking) keeps its stored owner's-terms amounts,
 * and they are converted here exactly as for the player ({@link CardPricing#playerPrice},
 * {@link CardPricing#remainingForPlayer}).
 *
 * <p>Every view also carries the cancellation deadline and what a cancellation right now
 * would refund, from the same {@link CancellationPolicy} and refundable payments that
 * {@code PaymentService} gives back on a real cancellation.
 */
@Component
@RequiredArgsConstructor
public class BookingResponseFactory {

    /** Money that arrived and was not given back. */
    private static final Set<Payment.Status> KEPT = Set.of(
            Payment.Status.PAID, Payment.Status.CAPTURED, Payment.Status.COMPLETED);

    private final BookingMapper bookingMapper;
    private final CancellationPolicy cancellationPolicy;
    private final PaymentService paymentService;
    private final CardPricing cardPricing;

    /** The player's view. */
    public BookingResponse of(Booking booking) {
        return of(booking, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public BookingResponse of(Booking booking, OffsetDateTime now) {
        BookingResponse response = build(booking, now,
                () -> paymentService.refundableCardTotal(booking.getId()));
        if (booking.getPrice() != null) {
            setPlayerAmounts(response, booking, cardPricing);
        }
        return response;
    }

    /** price, paidAmount and remainingAmount in the player's terms, adding up for every booking. */
    private static void setPlayerAmounts(BookingResponse response, Booking booking, CardPricing pricing) {
        long price = pricing.playerPrice(booking);
        long remaining = Math.min(price, pricing.remainingForPlayer(booking));
        response.setPrice(price);
        response.setRemainingAmount(remaining);
        response.setPaidAmount(price - remaining);
    }

    public List<BookingResponse> of(List<Booking> bookings) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return bookings.stream().map(booking -> of(booking, now)).toList();
    }

    /** The owner's view of one booking. */
    public BookingResponse forOwner(Booking booking) {
        return forOwner(booking, OffsetDateTime.now(ZoneOffset.UTC),
                booking.getId() == null ? List.of() : paymentService.paymentsOf(booking.getId()), cardPricing);
    }

    public List<BookingResponse> forOwner(List<Booking> bookings) {
        return bookings.stream().map(this::forOwner).toList();
    }

    /**
     * The owner's view for one row of a list, from what the list has already loaded: the
     * booking's payments and the settings read once ({@link CardPricing#snapshot()}). Costs
     * no query of its own.
     */
    public BookingResponse forOwner(Booking booking, OffsetDateTime now, List<Payment> payments, CardPricing pricing) {
        BookingResponse response = build(booking, now, () -> payments.stream()
                .filter(PaymentService::isRefundableCardPayment)
                .mapToLong(Payment::getAmount)
                .sum());
        if (booking.getPrice() != null) {
            // The remainder to collect in cash: the same amount the player would pay in the app.
            setPlayerAmounts(response, booking, pricing);
            response.setOwnerPrice(booking.getPrice());
        }
        List<Payment> kept = payments.stream()
                .filter(p -> KEPT.contains(p.getStatus()))
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .toList();
        response.setPaidInApp(kept.stream()
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .mapToLong(Payment::getAmount)
                .sum());
        response.setOwnerReceives(kept.stream()
                .mapToLong(p -> p.getMethod() == Payment.Method.CASH
                        ? p.getAmount()
                        : pricing.ownerReceives(p.getOwnerAmount() == null ? 0L : p.getOwnerAmount()))
                .sum());
        return response;
    }

    /** The admin's view: the owner's, plus what ATMOS takes of the card money kept. */
    public BookingResponse forAdmin(Booking booking, OffsetDateTime now, List<Payment> payments, CardPricing pricing) {
        BookingResponse response = forOwner(booking, now, payments, pricing);
        response.setAtmosFee(payments.stream()
                .filter(p -> KEPT.contains(p.getStatus()))
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .mapToLong(pricing::atmosFee)
                .sum());
        return response;
    }

    private BookingResponse build(Booking booking, OffsetDateTime now, LongSupplier refundable) {
        BookingResponse response = bookingMapper.toResponse(booking);
        if (booking.getStartsAt() == null || booking.getStadium() == null) {
            return response; // not enough to say anything about cancelling
        }
        response.setCancellationDeadline(cancellationPolicy.deadline(booking));
        response.setRefundIfCancelledNow(cancellationPolicy.refundable(booking, now)
                ? refundable.getAsLong()
                : 0L);
        return response;
    }
}
