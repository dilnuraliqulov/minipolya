package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.AdminPaymentResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminPaymentSummaryResponse;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.AdminPaymentMapper;
import uz.minipolya.minipolya_backend.repository.AdminPaymentFilter;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.PaymentSpecifications;
import uz.minipolya.minipolya_backend.util.Pagination;
import uz.minipolya.minipolya_backend.util.Revenue;
import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every payment and refund, for the admin panel's Toʻlovlar page, and the period summary.
 * The summary is {@link RevenueService}'s — the same figures the overview shows.
 */
@Service
@RequiredArgsConstructor
public class AdminPaymentService {

    private final PaymentRepository paymentRepository;
    private final AdminPaymentMapper adminPaymentMapper;
    private final RevenueService revenueService;

    @Transactional(readOnly = true)
    public Page<AdminPaymentResponse> list(String search, String type, String status, String method,
                                           UUID stadiumId, LocalDate from, LocalDate to, int page, int size) {
        AdminPaymentFilter filter = new AdminPaymentFilter(search,
                parse(type, Payment.PaymentType::fromValue, "Invalid type"),
                parse(status, Payment.Status::fromValue, "Invalid status"),
                parse(method, Payment.Method::fromValue, "Invalid method"),
                stadiumId, TashkentDays.range(from, to));
        // Unsorted on purpose: the specification orders (failed refunds first, then newest).
        PageRequest pageable = Pagination.of(page, size, Sort.unsorted());
        return paymentRepository.findAll(PaymentSpecifications.adminFilter(filter), pageable)
                .map(adminPaymentMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public AdminPaymentResponse get(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        AdminPaymentResponse response = adminPaymentMapper.toResponse(payment);
        List<Payment> linked = payment.getRefundOfPayment() != null
                ? List.of(payment.getRefundOfPayment())
                : paymentRepository.findByRefundOfPaymentIdOrderByCreatedAtAsc(paymentId);
        response.setLinkedPayments(linked.stream().map(adminPaymentMapper::toLinked).toList());
        return response;
    }

    @Transactional(readOnly = true)
    public AdminPaymentSummaryResponse summary(LocalDate from, LocalDate to) {
        TashkentDays.Range range = TashkentDays.requiredRange(from, to);
        RevenueService.Summary summary = revenueService.between(range.from(), range.to());
        Revenue revenue = summary.revenue();
        return AdminPaymentSummaryResponse.builder()
                .appRevenue(revenue.appRevenue())
                .cardFees(revenue.cardFees())
                .ownerPayouts(revenue.ownerPayouts())
                .platformRevenue(revenue.platformRevenue())
                .refunded(summary.refunded())
                .held(revenueService.heldNow())
                .build();
    }

    private static <T> T parse(String value, Function<String, T> parser, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(message);
        }
    }
}
