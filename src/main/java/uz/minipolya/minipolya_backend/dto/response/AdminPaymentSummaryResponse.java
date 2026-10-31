package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Card money over a period, all tiyin, all present. {@code ownerPayouts + platformRevenue
 * + cardFees = appRevenue}. {@code refunded} is the card refunds completed in the period
 * (already taken off {@code appRevenue}), as a positive amount; {@code held} is everything
 * on hold on players' cards right now, whenever it was taken.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPaymentSummaryResponse {

    private long appRevenue;
    private long cardFees;
    private long ownerPayouts;
    private long platformRevenue;
    private long refunded;
    private long held;
}
