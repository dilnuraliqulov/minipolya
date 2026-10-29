package uz.minipolya.minipolya_backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateStadiumStatusRequest;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumResponse;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.AdminService;
import uz.minipolya.minipolya_backend.service.AdminStadiumService;

import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class AdminStadiumController {

    private final AdminService adminService;
    private final AdminStadiumService adminStadiumService;

    @GetMapping("/api/admin/stadiums")
    public ApiResponse<PageResponse<AdminStadiumResponse>> list(@RequestParam(required = false) String status,
                                                                @RequestParam(required = false) String search,
                                                                @RequestParam(required = false) String district,
                                                                @RequestParam(required = false) String format,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.from(
                adminStadiumService.list(status, search, district, format, null, page, size),
                Function.identity()));
    }

    /** Everything an admin reviews, whatever the stadium's status. */
    @GetMapping("/api/admin/stadiums/{id}")
    public ApiResponse<AdminStadiumDetailResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(adminStadiumService.get(id));
    }

    @PutMapping("/api/admin/stadiums/{id}/status")
    public ApiResponse<AdminStadiumResponse> updateStatus(@AuthenticationPrincipal UUID adminUserId,
                                                          @PathVariable UUID id,
                                                          @Valid @RequestBody AdminUpdateStadiumStatusRequest request) {
        return ApiResponse.ok(adminService.updateStadiumStatus(id, request, adminUserId), "Stadium status updated");
    }
}
