package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lean summary used in listings: one cover photo instead of the whole gallery, no
 * amenities. Everything per row (rating, cover photo, favorite flag) is loaded for the
 * whole page at once.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StadiumResponse {

    private UUID id;
    private String name;
    private String address;
    /** The Tashkent district; absent for stadiums added before it existed. */
    private String district;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String format;
    private String surfaceType;
    /** The stadium's first photo (lowest position); absent when it has none. */
    private String coverPhotoUrl;
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
    private String status;
    /** Why the stadium is rejected or blocked — for its owner and admins; absent otherwise. */
    private String statusReason;
    private String ownerName;

    @JsonProperty("isActive")
    private boolean isActive;

    /** Computed from reviews — null when the stadium has no reviews yet. */
    private Double averageRating;
    private Integer reviewCount;

    /** Player lists, for a signed-in caller only: whether they have favorited this stadium. */
    @JsonProperty("isFavorited")
    private Boolean favorited;
}
