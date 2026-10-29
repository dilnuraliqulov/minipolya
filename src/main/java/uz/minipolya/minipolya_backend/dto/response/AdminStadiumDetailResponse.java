package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Everything an admin needs to review a stadium, in any status: every
 * {@link StadiumDetailResponse} field — the same mapping players get, with the owner's
 * prices ({@code pricePerHour} and {@code netPrice} net, {@code price} by card) — plus the
 * admin list's figures and the active pricing rules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminStadiumDetailResponse {

    @JsonUnwrapped
    private StadiumDetailResponse stadium;

    /** The first photo by position; absent when there are none. */
    private String photoUrl;
    private Integer cancellationHours;
    private Double averageRating;
    private Integer reviewCount;
    private Integer totalBookings;
    /** Tiyin: card money kept on this stadium's bookings and games, all time. */
    private Long totalRevenue;

    /** Active rules, each with {@code pricePerHour}/{@code netPrice} net and {@code price} by card. */
    private List<PricingRuleResponse> pricingRules;
}
