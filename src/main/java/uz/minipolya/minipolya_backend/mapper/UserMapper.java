package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import uz.minipolya.minipolya_backend.dto.request.UpdateUserRequest;
import uz.minipolya.minipolya_backend.dto.response.UserResponse;
import uz.minipolya.minipolya_backend.entity.User;

/**
 * Entity ↔ DTO mapping for users. Builder is disabled so MapStruct uses
 * setters, which keeps the boolean {@code isOwner}/{@code isAdmin} property
 * names aligned on both sides (Lombok's {@code isX} getter/setter convention).
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface UserMapper {

    UserResponse toResponse(User user);

    /** Applies only the non-null fields from the request onto the existing user. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateUserFromRequest(UpdateUserRequest request, @MappingTarget User user);
}
