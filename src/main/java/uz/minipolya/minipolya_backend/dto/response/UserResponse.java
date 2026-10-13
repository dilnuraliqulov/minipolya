package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Public-facing user projection. Never expose the {@link uz.minipolya.minipolya_backend.entity.User}
 * entity directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {

    private Long id;
    private String phone;
    private String name;
    private Long telegramId;
    private String language;

    @JsonProperty("isOwner")
    private boolean isOwner;

    @JsonProperty("isAdmin")
    private boolean isAdmin;

    private OffsetDateTime createdAt;
}
