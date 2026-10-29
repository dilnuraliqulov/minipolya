package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.AdminUserResponse;
import uz.minipolya.minipolya_backend.entity.User;

/**
 * A user as the admin list shows them. The builder is disabled so MapStruct uses setters,
 * keeping Lombok's boolean {@code isX} properties aligned. The counts come from other
 * rows, loaded for a whole page at once by {@code AdminUserService}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface AdminUserMapper {

    @Mapping(target = "bookingCount", ignore = true)
    @Mapping(target = "stadiumCount", ignore = true)
    AdminUserResponse toResponse(User user);
}
