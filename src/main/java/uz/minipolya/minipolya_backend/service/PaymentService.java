package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Payment lifecycle: initiate a checkout, handle the provider webhook, record
 * cash, and issue refunds. Completing a payment confirms its booking. Refunds
 * are always new rows with negative amounts — original rows are never mutated.
 *
 * <p>Does not depend on {@link BookingService}; it confirms bookings directly
 * so the one-way dependency BookingService → PaymentService (for refunds) stays
 * acyclic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Set<String> SUCCESS_TOKENS = Set.of("completed", "success", "paid", "ok", "1");

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PaymentMapper paymentMapper;
    private final List<PaymentProvider> paymentProviders;

    @Transactional
    public PaymentInitResponse initiateBookingPayment(Long bookingId, String providerName, Long userId) {
        Payment.Provider provider = parseCheckoutProvider(providerName);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (booking.getUser() == null || !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking does not belong to you");
        }
        if (!"pending".equals(booking.getStatus())) {
            throw new BadRequestException("Booking is not awaiting payment");
        }

        Payment payment = Payment.builder()
                .booking(booking)
                .user(booking.getUser())
                .amount(booking.getPrice())
                .paymentType(Payment.PaymentType.BOOKING)
                .provider(provider)
                .status(Payment.Status.PENDING)
                .build();
        Payment saved = paymentRepository.saveAndFlush(payment);

        // Mock txn id derived from our payment id; the webhook echoes it back.
        // TODO: Replace with the transaction id returned by the real provider.
        saved.setProviderTxnId("mock-" + saved.getId());
        paymentRepository.save(saved);

        String checkoutUrl = resolveProvider(provider).createCheckoutUrl(saved.getId());
        log.info("Payment initiated: id={} bookingId={} userId={} provider={}",
                saved.getId(), bookingId, userId, provider.getValue());
        return PaymentInitResponse.builder()
                .paymentId(saved.getId())
                .checkoutUrl(checkoutUrl)
                .provider(provider.getValue())
                .build();
    }

    @Transactional
    public Payment handleProviderCallback(String providerName, String providerTxnId, String providerStatus) {
        Payment.Provider provider = parseProvider(providerName);
        Payment payment = paymentRepository.findByProviderAndProviderTxnId(provider, providerTxnId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment found for transaction " + providerTxnId));

        if (payment.getStatus() == Payment.Status.COMPLETED) {
            return payment; // idempotent: duplicate webhook for an already-completed payment
        }

        if (isSuccess(providerStatus)) {
            payment.setStatus(Payment.Status.COMPLETED);
            paymentRepository.save(payment);
            if (payment.getBooking() != null) {
                confirmPaidBooking(payment.getBooking());
            }
            log.info("Payment completed: id={} provider={} txn={}",
                    payment.getId(), provider.getValue(), providerTxnId);
        } else {
            payment.setStatus(Payment.Status.FAILED);
            paymentRepository.save(payment);
            log.info("Payment failed: id={} provider={} txn={} providerStatus={}",
                    payment.getId(), provider.getValue(), providerTxnId, providerStatus);
        }
        return payment;
    }

    @Transactional
    public Payment createRefund(Long originalPaymentId, int refundAmount, String reason) {
        Payment original = paymentRepository.findById(originalPaymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (original.getStatus() != Payment.Status.COMPLETED) {
            throw new BadRequestException("Only a completed payment can be refunded");
        }

        Payment refund = Payment.builder()
                .booking(original.getBooking())
                .game(original.getGame())
                .user(original.getUser())
                .amount(-Math.abs(refundAmount))
                .paymentType(Payment.PaymentType.REFUND)
                .provider(original.getProvider())
                .status(Payment.Status.COMPLETED)
                .refundReason(reason)
                .refundOfPayment(original)
                .build();
        Payment savedRefund = paymentRepository.save(refund);

        original.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(original);

        log.info("Refund created: id={} originalId={} amount={}",
                savedRefund.getId(), originalPaymentId, savedRefund.getAmount());
        return savedRefund;
    }

    /** Refund the completed booking payment, if one exists. Called by BookingService on cancellation. */
    @Transactional
    public Payment refundBookingPayment(Long bookingId, int refundAmount, String reason) {
        Optional<Payment> original = paymentRepository.findByBookingIdAndPaymentTypeAndStatus(
                bookingId, Payment.PaymentType.BOOKING, Payment.Status.COMPLETED);
        if (original.isEmpty()) {
            log.warn("No completed booking payment to refund for booking {}", bookingId);
            return null;
        }
        return createRefund(original.get().getId(), refundAmount, reason);
    }

    @Transactional
    public Payment recordCashPayment(Long bookingId, Long ownerUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getStadium().getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        User owner = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Payment payment = Payment.builder()
                .booking(booking)
                .user(owner)
                .amount(booking.getPrice())
                .paymentType(Payment.PaymentType.BOOKING)
                .provider(Payment.Provider.CASH)
                .status(Payment.Status.COMPLETED)
                .build();
        Payment saved = paymentRepository.save(payment);

        confirmPaidBooking(booking);
        log.info("Cash payment recorded: id={} bookingId={} ownerId={}", saved.getId(), bookingId, ownerUserId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> getUserPayments(Long userId, Pageable pageable) {
        return paymentRepository.findByUserId(userId, pageable).map(paymentMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long userId, Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!payment.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This payment does not belong to you");
        }
        return paymentMapper.toResponse(payment);
    }

    // ---- Helpers ----

    private void confirmPaidBooking(Booking booking) {
        booking.setStatus("confirmed");
        booking.setExpiresAt(null);
        bookingRepository.save(booking);
    }

    private Payment.Provider parseCheckoutProvider(String providerName) {
        Payment.Provider provider = parseProvider(providerName);
        if (provider == Payment.Provider.CASH) {
            throw new BadRequestException("Cash is recorded by the owner, not initiated as a checkout");
        }
        return provider;
    }

    private Payment.Provider parseProvider(String providerName) {
        try {
            return Payment.Provider.fromValue(providerName);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("Unknown payment provider: " + providerName);
        }
    }

    private PaymentProvider resolveProvider(Payment.Provider provider) {
        return paymentProviders.stream()
                .filter(p -> p.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unsupported provider: " + provider.getValue()));
    }

    private boolean isSuccess(String providerStatus) {
        return providerStatus == null
                || providerStatus.isBlank()
                || SUCCESS_TOKENS.contains(providerStatus.trim().toLowerCase(Locale.ROOT));
    }
}
