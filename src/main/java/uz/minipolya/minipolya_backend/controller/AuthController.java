package uz.minipolya.minipolya_backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.AdminLoginRequest;
import uz.minipolya.minipolya_backend.dto.request.TelegramAuthRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.service.AdminAuthService;
import uz.minipolya.minipolya_backend.service.AuthService;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AdminAuthService adminAuthService;

    @PostMapping("/telegram")
    public ApiResponse<AuthResponse> telegram(@Valid @RequestBody TelegramAuthRequest request) {
        return ApiResponse.ok(authService.authenticateTelegram(request.getInitData()));
    }

    /**
     * Admin web panel sign-in with a username and password. Public, like the rest of
     * {@code /api/auth/**}; the IP is the direct peer address (configure
     * {@code server.forward-headers-strategy} when running behind a reverse proxy).
     */
    @PostMapping("/admin/login")
    public ApiResponse<AuthResponse> adminLogin(@Valid @RequestBody AdminLoginRequest request,
                                                HttpServletRequest httpRequest) {
        return ApiResponse.ok(adminAuthService.login(request, httpRequest.getRemoteAddr()));
    }
}
