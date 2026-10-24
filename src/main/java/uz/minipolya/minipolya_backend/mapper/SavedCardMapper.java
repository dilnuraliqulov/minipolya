package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import uz.minipolya.minipolya_backend.dto.response.SavedCardResponse;
import uz.minipolya.minipolya_backend.entity.SavedCard;

/**
 * Saved card entity → DTO. {@code providerToken} has no counterpart on the
 * response on purpose, so the token cannot leak through the API.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface SavedCardMapper {

    SavedCardResponse toResponse(SavedCard card);
}
