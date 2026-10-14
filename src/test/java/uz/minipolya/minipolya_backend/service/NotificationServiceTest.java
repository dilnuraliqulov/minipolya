package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.dto.response.NotificationResponse;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.NotificationMapper;
import uz.minipolya.minipolya_backend.repository.NotificationRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID NOTIFICATION_ID = UUID.randomUUID();
    private static final UUID BOOKING_REFERENCE_ID = UUID.randomUUID();
    private static final UUID GAME_REFERENCE_ID = UUID.randomUUID();

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private TelegramBotService telegramBotService;
    private NotificationMapper notificationMapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        telegramBotService = mock(TelegramBotService.class);
        notificationMapper = mock(NotificationMapper.class);
        service = new NotificationService(notificationRepository, userRepository,
                telegramBotService, notificationMapper);
    }

    @Test
    void notifyCreatesNotificationWithCorrectFields() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(User.builder().id(USER_ID).name("Player").build()));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification result = service.notify(USER_ID, Notification.Type.BOOKING_CONFIRMED, "Title", "Body",
                Notification.ReferenceType.BOOKING, BOOKING_REFERENCE_ID);

        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getType()).isEqualTo(Notification.Type.BOOKING_CONFIRMED);
        assertThat(result.getTitle()).isEqualTo("Title");
        assertThat(result.getReferenceType()).isEqualTo(Notification.ReferenceType.BOOKING);
        assertThat(result.getReferenceId()).isEqualTo(BOOKING_REFERENCE_ID);
        assertThat(result.isSentViaTelegram()).isFalse();
    }

    @Test
    void notifyPushesViaTelegramWhenUserHasChatId() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().id(USER_ID).name("Player").telegramId(123456L).build()));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(telegramBotService.sendMessage(anyLong(), anyString())).thenReturn(true);

        Notification result = service.notify(USER_ID, Notification.Type.GAME_CONFIRMED, "T", "B",
                Notification.ReferenceType.GAME, GAME_REFERENCE_ID);

        assertThat(result.isSentViaTelegram()).isTrue();
        verify(telegramBotService).sendMessage(anyLong(), anyString());
    }

    @Test
    void getNotificationsReturnsMappedPage() {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(new Notification())));
        when(notificationMapper.toResponse(any())).thenReturn(new NotificationResponse());

        assertThat(service.getNotifications(USER_ID, Pageable.ofSize(20)).getContent()).hasSize(1);
    }

    @Test
    void getUnreadCountReturnsRepositoryCount() {
        when(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).thenReturn(5);

        assertThat(service.getUnreadCount(USER_ID)).isEqualTo(5);
    }

    @Test
    void markAsReadFlagsNotification() {
        Notification notification = Notification.builder().id(NOTIFICATION_ID).userId(USER_ID).isRead(false).build();
        when(notificationRepository.findById(NOTIFICATION_ID)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markAsRead(NOTIFICATION_ID, USER_ID);

        assertThat(notification.isRead()).isTrue();
    }

    @Test
    void markAllAsReadDelegatesToRepository() {
        service.markAllAsRead(USER_ID);

        verify(notificationRepository).markAllAsRead(USER_ID);
    }
}
