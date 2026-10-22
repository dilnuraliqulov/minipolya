package uz.minipolya.minipolya_backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.ReviewApplicationRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerApplicationResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.OwnerApplicationService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.UUID;
import java.util.function.Function;

/**
 * Admin review of owner applications. The {@code /api/admin/**} path is gated to
 * ROLE_ADMIN in SecurityConfig; {@code reviewApplication} re-checks is_admin in
 * the service against the live DB state.
 */
@RestController
@RequiredArgsConstructor
public class AdminOwnerApplicationController {

    private final OwnerApplicationService applicationService;

    @GetMapping("/api/admin/owner-applications")
    public ApiResponse<PageResponse<OwnerApplicationResponse>> all(@RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                applicationService.getAllApplications(pageable), Function.identity()));
    }

    @GetMapping("/api/admin/owner-applications/pending")
    public ApiResponse<PageResponse<OwnerApplicationResponse>> pending(@RequestParam(defaultValue = "0") int page,
                                                                       @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                applicationService.getPendingApplications(pageable), Function.identity()));
    }

    @GetMapping("/api/admin/owner-applications/{id}")
    public ApiResponse<OwnerApplicationResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(applicationService.getApplication(id));
    }

    @PutMapping("/api/admin/owner-applications/{id}/review")
    public ApiResponse<OwnerApplicationResponse> review(@AuthenticationPrincipal UUID adminUserId,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody ReviewApplicationRequest request) {
        return ApiResponse.ok(applicationService.reviewApplication(id, request, adminUserId), "Application reviewed");
    }
}
