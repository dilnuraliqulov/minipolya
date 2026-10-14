package uz.minipolya.minipolya_backend.controller.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.AuditLogResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.AuditService;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Admin-only audit log views. {@code /api/admin/**} is gated to ROLE_ADMIN in SecurityConfig. */
@RestController
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditService auditService;

    @GetMapping("/api/admin/audit-log")
    public ApiResponse<PageResponse<AuditLogResponse>> all(@RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(auditService.getAll(pageable), Function.identity()));
    }

    @GetMapping("/api/admin/audit-log/user/{userId}")
    public ApiResponse<PageResponse<AuditLogResponse>> byUser(@PathVariable UUID userId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(auditService.getByUser(userId, pageable), Function.identity()));
    }

    @GetMapping("/api/admin/audit-log/entity/{type}/{id}")
    public ApiResponse<List<AuditLogResponse>> byEntity(@PathVariable String type,
                                                        @PathVariable UUID id) {
        return ApiResponse.ok(auditService.getByEntity(type, id));
    }
}
