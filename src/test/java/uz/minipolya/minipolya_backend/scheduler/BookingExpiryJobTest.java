package uz.minipolya.minipolya_backend.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditActorType;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.service.AuditService;
import uz.minipolya.minipolya_backend.service.NotificationService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingExpiryJobTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private BookingExpiryJob job;

    @Test
    void expiresEveryOverduePendingBooking() {
        Booking a = Booking.builder().id(UUID.randomUUID()).status("pending").build();
        Booking b = Booking.builder().id(UUID.randomUUID()).status("pending").build();
        when(bookingRepository.findExpirablePending(eq("pending"), any(OffsetDateTime.class),
                eq(Payment.Method.CASH), eq(BookingExpiryJob.PAYMENT_IN_FLIGHT)))
                .thenReturn(List.of(a, b));

        job.expirePendingBookings();

        assertThat(a.getStatus()).isEqualTo("expired");
        assertThat(b.getStatus()).isEqualTo("expired");
        verify(bookingRepository).saveAll(List.of(a, b));

        // One SYSTEM entry per booking: no user, the status change and why.
        assertThat(Audited.entries(auditService, AuditAction.BOOKING_EXPIRED))
                .hasSize(2)
                .allSatisfy(entry -> {
                    assertThat(entry.getActorType()).isEqualTo(AuditActorType.SYSTEM);
                    assertThat(entry.getActorId()).isNull();
                    assertThat(entry.getChanges()).containsExactly(AuditChange.of("status", "pending", "expired"));
                    assertThat(entry.getReason()).isEqualTo("Not paid within the hold time");
                })
                .extracting(AuditEntry::getEntityId)
                .containsExactly(a.getId(), b.getId());
    }

    @Test
    void doesNothingWhenNoneAreOverdue() {
        when(bookingRepository.findExpirablePending(eq("pending"), any(OffsetDateTime.class),
                eq(Payment.Method.CASH), eq(BookingExpiryJob.PAYMENT_IN_FLIGHT)))
                .thenReturn(List.of());

        job.expirePendingBookings();

        verify(bookingRepository, never()).saveAll(any());
    }

    /**
     * The job only ever sees what the query gives it, and the query leaves out a booking
     * whose card payment is still on its way — so a payment ATMOS has taken but not clearly
     * answered ({@code STPIMS-ERR-041}) can never lose the player their slot. The statuses
     * that mean "on its way" are asserted here because the query is built from them.
     */
    @Test
    void asksOnlyForBookingsWhoseCardPaymentIsNotStillOnItsWay() {
        assertThat(BookingExpiryJob.PAYMENT_IN_FLIGHT)
                .containsExactly(Payment.Status.PROCESSING, Payment.Status.CAPTURE_PENDING);

        job.expirePendingBookings();

        // The query the job asks is the one that leaves those bookings out: there is no
        // plain "every overdue pending booking" query left for it to reach for.
        verify(bookingRepository).findExpirablePending(eq("pending"), any(OffsetDateTime.class),
                eq(Payment.Method.CASH), eq(BookingExpiryJob.PAYMENT_IN_FLIGHT));
    }
}
