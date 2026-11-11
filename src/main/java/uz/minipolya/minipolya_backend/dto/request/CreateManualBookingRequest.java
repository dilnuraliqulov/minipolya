package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.util.StadiumRules;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateManualBookingRequest {

    /** Supplied by the {stadiumId} path variable; a value sent in the body is overwritten. */
    private UUID stadiumId;

    @NotBlank
    private String clientName;

    @NotBlank
    @Pattern(regexp = "^\\+998\\d{9}$", message = "clientPhone must be in the format +998XXXXXXXXX")
    private String clientPhone;

    @NotNull
    private OffsetDateTime startsAt;

    /** Optional: a booking is exactly one hour. Absent or 1; anything else is 400. */
    private Integer duration;

    private String notes;

    /** The owner's price for this hour in tiyin; absent means the stadium's own price for it. */
    @Positive
    @Max(StadiumRules.PRICE_MAX)
    private Long price;

    /** "unpaid" (the default: players pay after the game) or "paid" (cash already taken). */
    @Pattern(regexp = "unpaid|paid", message = "paymentStatus must be unpaid or paid")
    private String paymentStatus;
}
