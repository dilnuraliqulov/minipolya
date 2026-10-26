package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import uz.minipolya.minipolya_backend.dto.response.PlatformSettingsResponse;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;

/** Platform settings entity → DTO. */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface PlatformSettingsMapper {

    PlatformSettingsResponse toResponse(PlatformSettings settings);
}
