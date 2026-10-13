package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The stadium's cancellation policy — players must see this before confirming
 * a booking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancellationPolicyResponse {

    private int fullRefundHours;
    private int partialRefundHours;
    private int partialRefundPercent;
}
