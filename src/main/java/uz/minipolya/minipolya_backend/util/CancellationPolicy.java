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
        return booking.getStartsAt().minusHours(booking.getStadium().getCancellationHours());
    }

    /** Whether cancelling at {@code now} earns a refund. */
    public boolean refundable(Booking booking, OffsetDateTime now) {
        return !now.isAfter(deadline(booking));
    }

    /** Whether cancelling right now earns a refund. */
    public boolean refundableNow(Booking booking) {
        return refundable(booking, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
