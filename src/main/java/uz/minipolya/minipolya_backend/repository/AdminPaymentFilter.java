package uz.minipolya.minipolya_backend.repository;

import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.util.UUID;

/**
 * The admin payment list's filters; every one optional, combined with AND. {@code statuses}
 * is any of them (empty = all): the "Ushlab turilgan" tab asks for every hold still on a card
 * or waiting on someone — held, capture_pending, return_pending and needs_review.
 */
public record AdminPaymentFilter(String search, Payment.PaymentType type, java.util.Set<Payment.Status> statuses,
                                 Payment.Method method, UUID stadiumId, TashkentDays.Range createdAt) {

    public AdminPaymentFilter {
        statuses = statuses == null ? java.util.Set.of() : java.util.Set.copyOf(statuses);
    }
}
