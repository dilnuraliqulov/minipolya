package uz.minipolya.minipolya_backend.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.ChangePasswordRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.service.AdminAuthService;

import java.util.UUID;

/** The signed-in admin's own account. {@code /api/admin/**} is gated to ROLE_ADMIN in SecurityConfig. */
@RestController
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminAuthService adminAuthService;

    /** Answers with a fresh token: every token issued before the change stops working. */
    @PostMapping("/api/admin/me/password")
    public ApiResponse<AuthResponse> changePassword(@AuthenticationPrincipal UUID adminUserId,
                                            @Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(adminAuthService.changePassword(adminUserId, request, httpRequest.getRemoteAddr()),
                "Parol o'zgartirildi");
    }
}
