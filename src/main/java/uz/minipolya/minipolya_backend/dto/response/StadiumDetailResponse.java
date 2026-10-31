package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full stadium view: owner info, photos, amenities and the cancellation policy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StadiumDetailResponse {

    private UUID id;
    private UUID ownerId;
    private String ownerName;
    private String ownerPhone;

    private String name;
    private String address;
    /** The Tashkent district; absent for stadiums added before it existed. */
    private String district;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String phone;
    private String description;
    private String format;
    private String surfaceType;
    private LocalTime openTime;
    private LocalTime closeTime;
    /**
     * Tiyin, one hour at the stadium's default price. Player responses: the one price the
     * player pays. Owner and admin responses: the owner's own price, as they set it.
     */
    private Long pricePerHour;

    /** Owner and admin responses only: what players see and pay for that hour. */
    private Long playerPrice;
    /** Admin responses only: what the owner receives of one hour paid by card, after ATMOS. */
    private Long ownerReceives;
    /** Admin responses only: what ATMOS takes of one hour paid by card. */
    private Long atmosFee;
    /**
     * Tiyin; null means the player must pay the whole price up front. Player responses:
     * the deposit as players pay it. Owner and admin responses: the owner's own minimum.
     */
    private Long minBookingAmount;
    /** Owner and admin responses only: the deposit as players see and pay it. */
    private Long playerMinBookingAmount;

    private String status;
    /** Why the stadium is rejected or blocked — for its owner and admins; absent otherwise. */
    private String statusReason;

    @JsonProperty("isActive")
    private boolean isActive;

    /** Whether the requesting (authenticated) user has favorited this stadium. */
    @JsonProperty("isFavorited")
    private Boolean favorited;

    private CancellationPolicyResponse cancellationPolicy;
    private List<PhotoResponse> photos;
    private AmenitiesResponse amenities;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
