package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumClosureRequest;
import uz.minipolya.minipolya_backend.dto.response.StadiumClosureResponse;
import uz.minipolya.minipolya_backend.entity.StadiumClosure;

/** StadiumClosure entity ↔ DTO mapping. The stadium is attached by the service. */
@Mapper(componentModel = "spring")
public interface StadiumClosureMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "stadium", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    StadiumClosure toEntity(CreateStadiumClosureRequest request);

    @Mapping(source = "stadium.id", target = "stadiumId")
    StadiumClosureResponse toResponse(StadiumClosure closure);
}
