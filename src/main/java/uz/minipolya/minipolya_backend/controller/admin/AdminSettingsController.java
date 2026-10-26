package uz.minipolya.minipolya_backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.UpdatePlatformSettingsRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PlatformSettingsResponse;
import uz.minipolya.minipolya_backend.service.PlatformSettingsService;

import java.util.UUID;

/** Platform money settings. {@code /api/admin/**} is gated to ROLE_ADMIN in SecurityConfig. */
@RestController
@RequiredArgsConstructor
public class AdminSettingsController {

    private final PlatformSettingsService platformSettingsService;

    @GetMapping("/api/admin/settings")
    public ApiResponse<PlatformSettingsResponse> get() {
        return ApiResponse.ok(platformSettingsService.get());
    }

    @PutMapping("/api/admin/settings")
    public ApiResponse<PlatformSettingsResponse> update(@AuthenticationPrincipal UUID adminUserId,
                                                        @Valid @RequestBody UpdatePlatformSettingsRequest request) {
        return ApiResponse.ok(platformSettingsService.update(request, adminUserId), "Settings updated");
    }
}
