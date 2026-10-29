package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import uz.minipolya.minipolya_backend.dto.response.OwnerLegalDetailsResponse;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;

@Mapper(componentModel = "spring")
public interface OwnerLegalDetailsMapper {

    OwnerLegalDetailsResponse toResponse(OwnerLegalDetails details);
}
