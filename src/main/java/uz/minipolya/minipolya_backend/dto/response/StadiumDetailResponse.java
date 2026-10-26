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
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String phone;
    private String description;
    private String format;
    private String surfaceType;
    private LocalTime openTime;
    private LocalTime closeTime;
    private Long pricePerHour;

    /** Tiyin; null means the player must pay the whole price up front. */
    private Long minBookingAmount;

    private String status;

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
