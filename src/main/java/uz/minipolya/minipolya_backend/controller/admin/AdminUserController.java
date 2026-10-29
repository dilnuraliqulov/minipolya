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
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateUserRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateOwnerLegalDetailsRequest;
import uz.minipolya.minipolya_backend.dto.response.AdminUserResponse;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerLegalDetailsResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.AdminService;
import uz.minipolya.minipolya_backend.service.OwnerLegalDetailsService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminService adminService;
    private final OwnerLegalDetailsService ownerLegalDetailsService;

    @GetMapping("/api/admin/users")
    public ApiResponse<PageResponse<AdminUserResponse>> list(@RequestParam(required = false) String search,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(adminService.getUsers(search, pageable), Function.identity()));
    }

    @GetMapping("/api/admin/users/{id}")
    public ApiResponse<AdminUserResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(adminService.getUser(id));
    }

    @PutMapping("/api/admin/users/{id}")
    public ApiResponse<AdminUserResponse> update(@AuthenticationPrincipal UUID adminUserId,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody AdminUpdateUserRequest request) {
        return ApiResponse.ok(adminService.updateUser(id, request, adminUserId), "User updated");
    }

    /** The user's legal details and ATMOS contract; {@code data: null} when nothing was entered yet. */
    @GetMapping("/api/admin/users/{id}/legal-details")
    public ApiResponse<OwnerLegalDetailsResponse> legalDetails(@PathVariable UUID id) {
        return ApiResponse.ok(ownerLegalDetailsService.get(id));
    }

    @PutMapping("/api/admin/users/{id}/legal-details")
    public ApiResponse<OwnerLegalDetailsResponse> updateLegalDetails(
            @AuthenticationPrincipal UUID adminUserId,
            @PathVariable UUID id,
            @RequestBody(required = false) UpdateOwnerLegalDetailsRequest request) {
        UpdateOwnerLegalDetailsRequest body = request == null ? new UpdateOwnerLegalDetailsRequest() : request;
        return ApiResponse.ok(ownerLegalDetailsService.update(id, body, adminUserId), "Legal details updated");
    }
}
