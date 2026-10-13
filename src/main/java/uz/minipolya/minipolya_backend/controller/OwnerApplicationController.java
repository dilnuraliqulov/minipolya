package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreateOwnerApplicationRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerApplicationResponse;
import uz.minipolya.minipolya_backend.service.OwnerApplicationService;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class OwnerApplicationController {

    private final OwnerApplicationService applicationService;

    @PostMapping("/api/owner-applications")
    public ApiResponse<OwnerApplicationResponse> apply(@AuthenticationPrincipal Long userId,
                                                       @Valid @RequestBody CreateOwnerApplicationRequest request) {
        return ApiResponse.ok(applicationService.apply(request, userId), "Application submitted");
    }

    @GetMapping("/api/owner-applications/my")
    public ApiResponse<List<OwnerApplicationResponse>> myApplications(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(applicationService.getMyApplications(userId));
    }
}
