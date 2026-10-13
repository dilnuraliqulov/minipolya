package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Raw {@code initData} query string handed over by the Telegram Mini App.
 * Verified server-side via HMAC-SHA256 before any user is created.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramAuthRequest {

    @NotBlank(message = "initData is required")
    private String initData;
}
