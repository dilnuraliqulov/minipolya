package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import uz.minipolya.minipolya_backend.dto.request.AmenitiesRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.response.AmenitiesResponse;
import uz.minipolya.minipolya_backend.dto.response.PhotoResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumAmenities;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;

/**
 * Stadium entity ↔ DTO mapping. Builder is disabled so MapStruct uses setters,
 * keeping Lombok's boolean {@code isX} property names aligned across types.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface StadiumMapper {

    // Owner, status, flags, children and timestamps are set by the service, not copied from the request.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "owner", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "fullRefundHours", ignore = true)
    @Mapping(target = "partialRefundHours", ignore = true)
    @Mapping(target = "partialRefundPercent", ignore = true)
    @Mapping(target = "photos", ignore = true)
    @Mapping(target = "amenities", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Stadium toEntity(CreateStadiumRequest request);

    @Mapping(source = "owner.name", target = "ownerName")
    StadiumResponse toResponse(Stadium stadium);

    @Mapping(source = "owner.id", target = "ownerId")
    @Mapping(source = "owner.name", target = "ownerName")
    @Mapping(source = "owner.phone", target = "ownerPhone")
    @Mapping(source = "fullRefundHours", target = "cancellationPolicy.fullRefundHours")
    @Mapping(source = "partialRefundHours", target = "cancellationPolicy.partialRefundHours")
    @Mapping(source = "partialRefundPercent", target = "cancellationPolicy.partialRefundPercent")
    StadiumDetailResponse toDetailResponse(Stadium stadium);

    AmenitiesResponse toAmenitiesResponse(StadiumAmenities amenities);

    PhotoResponse toPhotoResponse(StadiumPhoto photo);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "stadium", ignore = true)
    StadiumAmenities toAmenities(AmenitiesRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(source = "isActive", target = "active")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "owner", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "photos", ignore = true)
    @Mapping(target = "amenities", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateStadiumFromRequest(UpdateStadiumRequest request, @MappingTarget Stadium stadium);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "stadium", ignore = true)
    void updateAmenities(AmenitiesRequest request, @MappingTarget StadiumAmenities amenities);
}
