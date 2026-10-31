package uz.minipolya.minipolya_backend.controller.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.AuditLogResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.AuditService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The audit log, read-only. {@code /api/admin/**} is gated to ROLE_ADMIN in SecurityConfig.
 * There is deliberately no endpoint that changes or deletes an entry.
 */
@RestController
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditService auditService;

    /**
     * Newest first. {@code action} may repeat ({@code ?action=A&action=B}) or be
     * comma-separated; {@code from}/{@code to} are Tashkent calendar days, both inclusive.
     */
    @GetMapping("/api/admin/audit-log")
    public ApiResponse<PageResponse<AuditLogResponse>> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) List<String> action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // The order is fixed by the search itself (newest first); this only validates paging.
        PageRequest pageable = Pagination.of(page, size, Sort.unsorted());
        return ApiResponse.ok(PageResponse.from(
                auditService.search(actor, actorType, action, entityType, entityId, from, to, pageable),
                Function.identity()));
    }

    /** Every action code, for a filter dropdown. */
    @GetMapping("/api/admin/audit-log/actions")
    public ApiResponse<List<String>> actions() {
        return ApiResponse.ok(auditService.actionCodes());
    }
}
