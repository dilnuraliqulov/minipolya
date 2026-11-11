package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminStadiumResponse {

    private UUID id;
    private String name;
    private String address;
    /** The Tashkent district; absent for stadiums added before it existed. */
    private String district;
    /** The first photo by position; absent when there are none. */
    private String photoUrl;
    /** Tiyin: the stadium's base hourly price — the owner's own price, as they set it. */
    private Long pricePerHour;
    /** Tiyin: what players see and pay for that hour. */
    private Long playerPrice;
    /** Tiyin: what the owner receives of that hour paid by card, after ATMOS. */
    private Long ownerReceives;
    /** Tiyin: what ATMOS takes of that hour paid by card. */
    private Long atmosFee;
    /** The owner's ATMOS contract: none, pending or signed. Approval needs signed. */
    private OwnerLegalDetails.ContractStatus ownerContractStatus;
    private String ownerName;
    private UUID ownerId;
    private String status;
    /** Why the stadium is rejected or blocked — for its owner and admins; absent otherwise. */
    private String statusReason;
    private String format;
    private String surfaceType;
    private double averageRating;
    private int reviewCount;
    private int totalBookings;
    private long totalRevenue;
    private OffsetDateTime createdAt;
}
