package uz.minipolya.minipolya_backend.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.repository.BookingRepository;

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
class BookingCompletionJobTest {

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private BookingCompletionJob job;

    @Test
    void completesEveryConfirmedBookingThatHasAlreadyEnded() {
        Booking a = Booking.builder().id(UUID.randomUUID()).status("confirmed").build();
        Booking b = Booking.builder().id(UUID.randomUUID()).status("confirmed").build();
        when(bookingRepository.findByStatusAndEndsAtBefore(eq("confirmed"), any(OffsetDateTime.class)))
                .thenReturn(List.of(a, b));

        job.completePlayedBookings();

        assertThat(a.getStatus()).isEqualTo("completed");
        assertThat(b.getStatus()).isEqualTo("completed");
        verify(bookingRepository).saveAll(List.of(a, b));
    }

    @Test
    void onlyConfirmedBookingsAreConsidered() {
        when(bookingRepository.findByStatusAndEndsAtBefore(eq("confirmed"), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        job.completePlayedBookings();

        verify(bookingRepository).findByStatusAndEndsAtBefore(eq("confirmed"), any(OffsetDateTime.class));
        verify(bookingRepository, never()).saveAll(any());
    }

    @Test
    void isIdempotentBecauseCompletedBookingsNoLongerMatch() {
        Booking already = Booking.builder().id(UUID.randomUUID()).status("completed").build();
        when(bookingRepository.findByStatusAndEndsAtBefore(eq("confirmed"), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        job.completePlayedBookings();

        assertThat(already.getStatus()).isEqualTo("completed");
        verify(bookingRepository, never()).saveAll(any());
    }
}
