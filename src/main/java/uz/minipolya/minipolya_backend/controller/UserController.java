package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.UpdateUserRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.UserResponse;
import uz.minipolya.minipolya_backend.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal UUID userId) {
        return ApiResponse.ok(userService.getCurrentUser(userId));
    }

    @PatchMapping("/me")
    public ApiResponse<UserResponse> updateMe(@AuthenticationPrincipal UUID userId,
                                              @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.ok(userService.updateProfile(userId, request));
    }
}
