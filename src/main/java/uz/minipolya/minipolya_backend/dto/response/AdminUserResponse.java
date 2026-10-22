package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminUserResponse {

    private UUID id;
    private String name;
    private String phone;
    private Long telegramId;
    private String language;

    @JsonProperty("isOwner")
    private boolean isOwner;

    @JsonProperty("isAdmin")
    private boolean isAdmin;

    @JsonProperty("isBlocked")
    private boolean isBlocked;

    private int bookingCount;
    private int stadiumCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastActiveAt;
}
