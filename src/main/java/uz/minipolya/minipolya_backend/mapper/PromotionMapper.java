package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.request.CreatePromotionRequest;
import uz.minipolya.minipolya_backend.dto.response.PromotionResponse;
import uz.minipolya.minipolya_backend.entity.Promotion;

/**
 * Promotion entity ↔ DTO mapping. Builder is disabled so MapStruct uses setters,
 * keeping Lombok's boolean {@code isActive} property aligned across types.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface PromotionMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "stadium", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Promotion toEntity(CreatePromotionRequest request);

    @Mapping(source = "stadium.id", target = "stadiumId")
    @Mapping(source = "stadium.name", target = "stadiumName")
    PromotionResponse toResponse(Promotion promotion);
}
