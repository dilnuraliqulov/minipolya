package uz.minipolya.minipolya_backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.AddStadiumNoteRequest;
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateStadiumStatusRequest;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumResponse;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumNoteResponse;
import uz.minipolya.minipolya_backend.service.AdminService;
import uz.minipolya.minipolya_backend.service.AdminStadiumService;
import uz.minipolya.minipolya_backend.service.StadiumNoteService;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class AdminStadiumController {

    private final AdminService adminService;
    private final AdminStadiumService adminStadiumService;
    private final StadiumNoteService stadiumNoteService;

    @GetMapping("/api/admin/stadiums")
    public ApiResponse<PageResponse<AdminStadiumResponse>> list(@RequestParam(required = false) String status,
                                                                @RequestParam(required = false) String search,
                                                                @RequestParam(required = false) String district,
                                                                @RequestParam(required = false) String format,
                                                                @RequestParam(required = false) UUID ownerId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.from(
                adminStadiumService.list(status, search, district, format, ownerId, page, size),
                Function.identity()));
    }

    /** Admins' internal notes on the stadium, newest first. Never shown to owners or players. */
    @GetMapping("/api/admin/stadiums/{id}/notes")
    public ApiResponse<List<StadiumNoteResponse>> notes(@PathVariable UUID id) {
        return ApiResponse.ok(stadiumNoteService.list(id));
    }

    @PostMapping("/api/admin/stadiums/{id}/notes")
    public ApiResponse<StadiumNoteResponse> addNote(@AuthenticationPrincipal UUID adminUserId,
                                                    @PathVariable UUID id,
                                                    @RequestBody(required = false) AddStadiumNoteRequest request) {
        return ApiResponse.ok(stadiumNoteService.add(id, request == null ? null : request.getText(), adminUserId),
                "Note added");
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
