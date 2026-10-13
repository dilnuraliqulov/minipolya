package uz.minipolya.minipolya_backend.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.service.NotificationService;

import java.time.OffsetDateTime;
import java.util.List;

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

    @InjectMocks
    private BookingExpiryJob job;

    @Test
    void expiresEveryOverduePendingBooking() {
        Booking a = Booking.builder().id(1L).status("pending").build();
        Booking b = Booking.builder().id(2L).status("pending").build();
        when(bookingRepository.findByStatusAndExpiresAtBefore(eq("pending"), any(OffsetDateTime.class)))
                .thenReturn(List.of(a, b));

        job.expirePendingBookings();

        assertThat(a.getStatus()).isEqualTo("expired");
        assertThat(b.getStatus()).isEqualTo("expired");
        verify(bookingRepository).saveAll(List.of(a, b));
    }

    @Test
    void doesNothingWhenNoneAreOverdue() {
        when(bookingRepository.findByStatusAndExpiresAtBefore(eq("pending"), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        job.expirePendingBookings();

        verify(bookingRepository, never()).saveAll(any());
    }
}
