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
 * Lean summary used in listings. Deliberately omits photos and amenities to
 * avoid lazy-loading per row — clients fetch those via the detail endpoint.
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
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String format;
    private String surfaceType;
    /**
     * One hour at the stadium's default price. Public responses: what the player pays by
     * card. Owner responses: the owner's net price, as they set it.
     */
    private Long pricePerHour;

    /** One hour at the default price by card, in tiyin: the net price grossed up by the card fee and rounded. */
    private Long price;
    /** The owner's own net price. Owner and admin responses only. */
    private Long netPrice;
    /** price − netPrice. */
    private Long cardFee;
    private String status;
    private String ownerName;

    @JsonProperty("isActive")
    private boolean isActive;

    /** Computed from reviews — null when the stadium has no reviews yet. */
    private Double averageRating;
    private Integer reviewCount;
}
