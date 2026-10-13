package uz.minipolya.minipolya_backend.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.NotificationRepository;
import uz.minipolya.minipolya_backend.service.NotificationService;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReminderJobTest {

    private BookingRepository bookingRepository;
    private NotificationRepository notificationRepository;
    private GameParticipantRepository participantRepository;
    private NotificationService notificationService;
    private ReminderJob job;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        participantRepository = mock(GameParticipantRepository.class);
        notificationService = mock(NotificationService.class);
        job = new ReminderJob(bookingRepository, notificationRepository, participantRepository, notificationService);
    }

    private Booking upcomingBooking() {
        return Booking.builder().id(1L).status("confirmed")
                .user(User.builder().id(7L).name("Player").build())
                .stadium(Stadium.builder().id(1L).name("Arena").build())
                .startsAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2))
                .build();
    }

    @Test
    void createsReminderForUpcomingBooking() {
        when(bookingRepository.findByStatusAndStartsAtBetween(eq("confirmed"), any(), any()))
                .thenReturn(List.of(upcomingBooking()));
        when(notificationRepository.existsByReferenceTypeAndReferenceIdAndType(any(), any(), any()))
                .thenReturn(false);

        job.sendReminders();

        verify(notificationService).notify(eq(7L), eq(Notification.Type.BOOKING_REMINDER),
                anyString(), anyString(), eq(Notification.ReferenceType.BOOKING), eq(1L));
    }

    @Test
    void doesNotDuplicateReminderWhenAlreadySent() {
        when(bookingRepository.findByStatusAndStartsAtBetween(eq("confirmed"), any(), any()))
                .thenReturn(List.of(upcomingBooking()));
        when(notificationRepository.existsByReferenceTypeAndReferenceIdAndType(any(), any(), any()))
                .thenReturn(true);

        job.sendReminders();

        verify(notificationService, never()).notify(anyLong(), any(), anyString(), anyString(), any(), anyLong());
    }
}
