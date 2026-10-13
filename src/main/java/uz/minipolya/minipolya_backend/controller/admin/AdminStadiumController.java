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
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateStadiumStatusRequest;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumResponse;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.AdminService;

import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class AdminStadiumController {

    private final AdminService adminService;

    @GetMapping("/api/admin/stadiums")
    public ApiResponse<PageResponse<AdminStadiumResponse>> list(@RequestParam(required = false) String status,
                                                                @RequestParam(required = false) String search,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(adminService.getStadiums(status, search, pageable), Function.identity()));
    }

    @GetMapping("/api/admin/stadiums/{id}")
    public ApiResponse<AdminStadiumResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(adminService.getStadium(id));
    }

    @PutMapping("/api/admin/stadiums/{id}/status")
    public ApiResponse<AdminStadiumResponse> updateStatus(@AuthenticationPrincipal Long adminUserId,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody AdminUpdateStadiumStatusRequest request) {
        return ApiResponse.ok(adminService.updateStadiumStatus(id, request, adminUserId), "Stadium status updated");
    }
}
