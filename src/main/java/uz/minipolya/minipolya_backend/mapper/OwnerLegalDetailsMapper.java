package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.OwnerContractStatusResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerLegalDetailsResponse;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;

@Mapper(componentModel = "spring")
public interface OwnerLegalDetailsMapper {

    OwnerLegalDetailsResponse toResponse(OwnerLegalDetails details);

    /** The owner's own view: legal form and contract status only, no tax or recipient id. */
    @Mapping(source = "atmosContractStatus", target = "ownerContractStatus")
    OwnerContractStatusResponse toContractStatus(OwnerLegalDetails details);
}
