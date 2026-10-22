package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.InitiatePaymentRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentResponse;
import uz.minipolya.minipolya_backend.service.PaymentService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/api/payments/initiate")
    public ApiResponse<PaymentInitResponse> initiate(@AuthenticationPrincipal UUID userId,
                                                     @Valid @RequestBody InitiatePaymentRequest request) {
        return ApiResponse.ok(
                paymentService.initiateBookingPayment(request.getBookingId(), request.getProvider(), userId));
    }

    @GetMapping("/api/payments/my")
    public ApiResponse<PageResponse<PaymentResponse>> myPayments(@AuthenticationPrincipal UUID userId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(paymentService.getUserPayments(userId, pageable), Function.identity()));
    }

    @GetMapping("/api/payments/{id}")
    public ApiResponse<PaymentResponse> detail(@AuthenticationPrincipal UUID userId,
                                               @PathVariable UUID id) {
        return ApiResponse.ok(paymentService.getPayment(userId, id));
    }
}
