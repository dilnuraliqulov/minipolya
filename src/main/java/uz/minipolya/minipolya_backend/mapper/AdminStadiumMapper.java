package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;

/**
 * A stadium as the admin list shows it. Ratings, counts, revenue, the card price and the
 * first photo come from other rows, which the list loads in bulk; {@code AdminStadiumService}
 * fills them in.
 */
@Mapper(componentModel = "spring")
public interface AdminStadiumMapper {

    @Mapping(source = "owner.name", target = "ownerName")
    @Mapping(source = "owner.id", target = "ownerId")
    @Mapping(target = "photoUrl", ignore = true)
    @Mapping(target = "price", ignore = true)
    @Mapping(target = "averageRating", ignore = true)
    @Mapping(target = "reviewCount", ignore = true)
    @Mapping(target = "totalBookings", ignore = true)
    @Mapping(target = "totalRevenue", ignore = true)
    AdminStadiumResponse toResponse(Stadium stadium);
}
