package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.Notification;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationResponse {

    private Long id;
    private Long userId;
    private Notification.Type type;
    private String title;
    private String message;
    private Notification.ReferenceType referenceType;
    private Long referenceId;

    @JsonProperty("isRead")
    private boolean isRead;

    @JsonProperty("sentViaTelegram")
    private boolean sentViaTelegram;

    private OffsetDateTime createdAt;
}
