package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An owner's legal details as an admin sets them. Checked by the service so each error
 * reads exactly as documented:
 * <ul>
 *   <li>{@code legalType} — {@code yatt}, {@code mchj} or {@code self_employed};</li>
 *   <li>{@code tin} — 9 digits, or 14 (the PINFL) for {@code self_employed};</li>
 *   <li>{@code atmosContractStatus} — {@code none}, {@code pending} or {@code signed};</li>
 *   <li>{@code atmosRecipientId} — 1–50 characters, required when signed, ignored otherwise.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateOwnerLegalDetailsRequest {

    private String legalType;
    private String tin;
    private String atmosContractStatus;
    private String atmosRecipientId;
}
