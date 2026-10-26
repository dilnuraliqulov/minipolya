package uz.minipolya.minipolya_backend.util;

import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.Booking;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * When a booking can still be cancelled for a refund. The window is configured
 * per stadium as {@code cancellation_hours}: cancel at or before the deadline
 * and every card payment of the booking is refunded in full; cancel later and
 * the booking is still cancelled but nothing is given back.
 *
 * <p>Pure and stateless — the one place this rule is expressed. Both the
 * cancellation itself and the {@code cancellationDeadline} /
 * {@code refundIfCancelledNow} fields on a booking response come from here, so
 * what a player is shown is what they will actually get.
 */
@Component
public class CancellationPolicy {

    /** The last moment a cancellation still earns a refund. */
    public OffsetDateTime deadline(Booking booking) {
        return deadline(booking.getStartsAt(), booking.getStadium().getCancellationHours());
    }

    /**
     * The same window for something that is not a booking — a waiting team
     * game holds no slot, so it has no booking to ask, but the stadium's rule
     * applies to it just the same.
     */
    public OffsetDateTime deadline(OffsetDateTime startsAt, int cancellationHours) {
        return startsAt.minusHours(cancellationHours);
    }

    /** Whether cancelling at {@code now} earns a refund. */
    public boolean refundable(Booking booking, OffsetDateTime now) {
        return !now.isAfter(deadline(booking));
    }

    /** Whether cancelling at {@code now} earns a refund, for a start time and window. */
    public boolean refundable(OffsetDateTime startsAt, int cancellationHours, OffsetDateTime now) {
        return !now.isAfter(deadline(startsAt, cancellationHours));
    }

    /** Whether cancelling right now earns a refund. */
    public boolean refundableNow(Booking booking) {
        return refundable(booking, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
