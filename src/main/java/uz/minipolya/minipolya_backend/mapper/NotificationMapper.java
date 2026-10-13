package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import uz.minipolya.minipolya_backend.dto.response.NotificationResponse;
import uz.minipolya.minipolya_backend.entity.Notification;

/**
 * Notification entity → DTO mapping. Builder is disabled so MapStruct uses
 * setters, keeping Lombok's boolean {@code isRead} property aligned across types.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface NotificationMapper {

    NotificationResponse toResponse(Notification notification);
}
