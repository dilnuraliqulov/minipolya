package uz.minipolya.minipolya_backend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.service.AuditService;
import uz.minipolya.minipolya_backend.service.NotificationService;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Expires unpaid holds. Every minute it flips {@code pending} bookings whose
 * {@code expires_at} has passed to {@code expired}, freeing the slot. Idempotent:
 * once expired a booking no longer matches the query.
 *
 * <p><b>Never a booking whose payment is still on its way.</b> A {@code processing} or
 * {@code capture_pending} card payment is one the provider has been asked about and has not
 * clearly answered — ATMOS answers {@code pay/apply} with "connection problem" for money it
 * has in fact taken — so the slot stays held until it is resolved
 * ({@link BookingRepository#findExpirablePending}). Nothing waits forever: an unconfirmed
 * charge is failed by {@code PaymentReconcileService} after its own abandonment window, and
 * the next run of this job expires the booking then.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryJob {

    /** A card payment in one of these has been asked for and not answered: the slot is still its. */
    static final List<Payment.Status> PAYMENT_IN_FLIGHT =
            List.of(Payment.Status.PROCESSING, Payment.Status.CAPTURE_PENDING);

    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expirePendingBookings() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Booking> expired = bookingRepository.findExpirablePending(
                "pending", now, Payment.Method.CASH, PAYMENT_IN_FLIGHT);
        if (expired.isEmpty()) {
            return;
        }
        expired.forEach(b -> b.setStatus("expired"));
        bookingRepository.saveAll(expired);

        for (Booking booking : expired) {
            auditService.record(AuditEntry.system(AuditAction.BOOKING_EXPIRED)
                    .entity(AuditEntityType.BOOKING, booking.getId(), AuditLabels.booking(booking))
                    .change("status", "pending", booking.getStatus())
                    .reason("Not paid within the hold time"));
            if (booking.getUser() != null) {
                safeNotify(booking);
            }
        }
        log.info("Expired {} unpaid booking(s)", expired.size());
    }

    private void safeNotify(Booking booking) {
        try {
            notificationService.notify(booking.getUser().getId(), Notification.Type.BOOKING_EXPIRED,
                    "Бронь истекла",
                    "Бронь в «" + booking.getStadium().getName() + "» истекла из-за неоплаты.",
                    Notification.ReferenceType.BOOKING, booking.getId());
        } catch (Exception ex) {
            log.warn("Booking-expired notification failed: {}", ex.toString());
        }
    }
}
