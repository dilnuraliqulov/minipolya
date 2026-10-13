package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByBookingId(Long bookingId);

    List<Payment> findByGameId(Long gameId);

    Page<Payment> findByUserId(Long userId, Pageable pageable);

    /** Looks up a payment by its provider transaction id — the webhook idempotency key. */
    Optional<Payment> findByProviderAndProviderTxnId(Payment.Provider provider, String providerTxnId);

    Optional<Payment> findByBookingIdAndPaymentTypeAndStatus(Long bookingId,
                                                             Payment.PaymentType paymentType,
                                                             Payment.Status status);
}
