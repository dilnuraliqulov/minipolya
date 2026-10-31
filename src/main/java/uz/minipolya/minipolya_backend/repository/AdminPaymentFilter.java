package uz.minipolya.minipolya_backend.repository;

import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.util.UUID;

/** The admin payment list's filters; every one optional, combined with AND. */
public record AdminPaymentFilter(String search, Payment.PaymentType type, Payment.Status status,
                                 Payment.Method method, UUID stadiumId, TashkentDays.Range createdAt) {
}
