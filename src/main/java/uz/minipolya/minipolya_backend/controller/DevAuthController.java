package uz.minipolya.minipolya_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.DevLoginRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.dto.response.DevUserResponse;
import uz.minipolya.minipolya_backend.service.DevAuthService;

import java.util.List;

/**
 * Dev-only sign-in (R19). Registered only under the {@code dev} profile: in every other
 * profile these paths do not exist and answer 404 "Not found".
 */
@RestController
@Profile("dev")
@RequiredArgsConstructor
public class DevAuthController {

    private final DevAuthService devAuthService;

    @GetMapping("/api/auth/dev/users")
    public ApiResponse<List<DevUserResponse>> users() {
        return ApiResponse.ok(devAuthService.users());
    }

    @PostMapping("/api/auth/dev/login")
    public ApiResponse<AuthResponse> login(@RequestBody DevLoginRequest request) {
        return ApiResponse.ok(devAuthService.login(request.getUserId()));
    }
}
