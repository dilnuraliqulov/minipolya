package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;

/**
 * R39: what an owner may see of their own legal details — the legal form and the contract
 * status, never the tax id or the ATMOS recipient id. {@code ownerContractStatus} is the
 * same value as {@code atmosContractStatus}, under the name the admin stadium list uses.
 * With nothing entered yet both are {@code none} and {@code legalType} is absent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OwnerContractStatusResponse {

    private OwnerLegalDetails.LegalType legalType;
    private OwnerLegalDetails.ContractStatus atmosContractStatus;
    private OwnerLegalDetails.ContractStatus ownerContractStatus;
}
