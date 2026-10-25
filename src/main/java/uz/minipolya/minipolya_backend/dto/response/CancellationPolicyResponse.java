package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The stadium's cancellation policy — players must see this before confirming
 * a booking. Cancelling at least {@code cancellationHours} before kick-off
 * refunds every card payment in full; later cancels the booking but refunds
 * nothing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancellationPolicyResponse {

    private int cancellationHours;
}
