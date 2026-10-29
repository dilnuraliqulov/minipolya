package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;

import java.time.OffsetDateTime;

/**
 * An owner's legal details for the admin panel. {@code atmosRecipientId} is present only
 * when the contract is signed; {@code legalType} and {@code tin} are absent only on a row
 * carried over from before legal details existed, until an admin fills them in.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OwnerLegalDetailsResponse {

    private OwnerLegalDetails.LegalType legalType;
    private String tin;
    private OwnerLegalDetails.ContractStatus atmosContractStatus;
    private String atmosRecipientId;
    private OffsetDateTime updatedAt;
    private String updatedByName;
}
