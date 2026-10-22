package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.OwnerApplicationResponse;
import uz.minipolya.minipolya_backend.entity.OwnerApplication;

/** OwnerApplication entity → DTO mapping. */
@Mapper(componentModel = "spring")
public interface OwnerApplicationMapper {

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    @Mapping(source = "user.phone", target = "userPhone")
    @Mapping(source = "reviewedBy.id", target = "reviewedBy")
    @Mapping(source = "reviewedBy.name", target = "reviewerName")
    OwnerApplicationResponse toResponse(OwnerApplication application);
}
