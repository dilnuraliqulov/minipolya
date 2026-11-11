package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial update of the platform settings — every field is optional and only
 * non-null values are applied. Ranges are checked in
 * {@link uz.minipolya.minipolya_backend.service.PlatformSettingsService} rather
 * than with bean validation, so that the message names the offending field and
 * nothing is written when one value is rejected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePlatformSettingsRequest {

    /** Tiyin, not negative. */
    private Long teamGamePlatformFee;

    /** Tiyin, not negative. */
    private Long casualGameServiceFee;

    /** Basis points, 0..1000 (150 = 1.5%). */
    private Integer cardFeeBps;

    /** Tiyin, 1..1 000 000 (10 000 = 100 so'm). */
    private Long paymentRoundingStep;

    /**
     * Tiyin: the minimum booking amount of every stadium without its own (T1). Must be more
     * than the team-game platform fee.
     */
    private Long defaultMinBookingAmount;
}
