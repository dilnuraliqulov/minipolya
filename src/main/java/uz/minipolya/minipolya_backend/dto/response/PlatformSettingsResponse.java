package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Platform-wide money settings. Fees are tiyin.
 * The platform takes nothing from ordinary bookings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSettingsResponse {

    private UUID id;
    private long teamGamePlatformFee;
    private long casualGameServiceFee;
    /** Basis points: 150 = 1.5%. */
    private int cardFeeBps;
    /** Tiyin. */
    private long paymentRoundingStep;
    private OffsetDateTime updatedAt;
}
