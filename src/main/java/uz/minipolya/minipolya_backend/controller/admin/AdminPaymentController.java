package uz.minipolya.minipolya_backend.controller.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.MarkRefundedRequest;
import uz.minipolya.minipolya_backend.dto.response.AdminPaymentResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminPaymentSummaryResponse;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.AdminPaymentService;
import uz.minipolya.minipolya_backend.service.RefundRecoveryService;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

/**
 * Payments and refunds for the admin panel. {@code from} / {@code to} are Tashkent days,
 * both inclusive. A payment's status history comes from the audit log.
 */
@RestController
@RequiredArgsConstructor
public class AdminPaymentController {

    private final AdminPaymentService adminPaymentService;
    private final RefundRecoveryService refundRecoveryService;

    @GetMapping("/api/admin/payments")
    public ApiResponse<PageResponse<AdminPaymentResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) UUID stadiumId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.from(
                adminPaymentService.list(search, type, status, method, stadiumId, from, to, page, size),
                Function.identity()));
    }

    @GetMapping("/api/admin/payments/summary")
    public ApiResponse<AdminPaymentSummaryResponse> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(adminPaymentService.summary(from, to));
    }

    @GetMapping("/api/admin/payments/{id}")
    public ApiResponse<AdminPaymentResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(adminPaymentService.get(id));
    }

    /** A failed refund, tried again at the provider. A decline is answered 200 with the new reason. */
    @PostMapping("/api/admin/payments/{id}/retry-refund")
    public ApiResponse<AdminPaymentResponse> retryRefund(@AuthenticationPrincipal UUID adminUserId,
                                                         @PathVariable UUID id) {
        refundRecoveryService.retry(id, adminUserId);
        return ApiResponse.ok(adminPaymentService.get(id), "Refund retried");
    }

    /** A failed refund whose money went back outside the platform (cash, bank transfer). */
    @PostMapping("/api/admin/payments/{id}/mark-refunded")
    public ApiResponse<AdminPaymentResponse> markRefunded(@AuthenticationPrincipal UUID adminUserId,
                                                          @PathVariable UUID id,
                                                          @RequestBody(required = false) MarkRefundedRequest request) {
        refundRecoveryService.markRefunded(id, request == null ? null : request.getReason(), adminUserId);
        return ApiResponse.ok(adminPaymentService.get(id), "Refund marked as refunded");
    }
}
