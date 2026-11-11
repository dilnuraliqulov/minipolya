package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PricingRuleResponse {

    private UUID id;
    private UUID stadiumId;
    private Integer dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    /**
     * Tiyin for one hour under this rule. Player responses: the one price the player pays.
     * Owner and admin responses: the owner's own price, as they set it.
     */
    private Long pricePerHour;

    /** Owner and admin responses only: what players see and pay for that hour. */
    private Long playerPrice;
    /** Admin responses only: what the owner receives of that hour paid by card, after ATMOS. */
    private Long ownerReceives;
    /** Admin responses only: what ATMOS takes of that hour paid by card. */
    private Long atmosFee;

    @JsonProperty("isActive")
    private boolean isActive;

    private OffsetDateTime createdAt;
}
