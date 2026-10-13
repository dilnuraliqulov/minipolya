package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.NotificationResponse;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.NotificationMapper;
import uz.minipolya.minipolya_backend.repository.NotificationRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

/**
 * Creates in-app notifications and best-effort pushes them via Telegram.
 *
 * <p>{@link #notify} runs in its own transaction (REQUIRES_NEW) so a
 * notification failure, caught by callers, never poisons the business
 * transaction that triggered it. Messages are Russian for now.
 * TODO: i18n support keyed on user.language.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final TelegramBotService telegramBotService;
    private final NotificationMapper notificationMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification notify(Long userId, Notification.Type type, String title, String message,
                               Notification.ReferenceType referenceType, Long referenceId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Skipping notification for missing user {}", userId);
            return null;
        }

        Notification notification = notificationRepository.save(Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .message(message)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .build());

        if (user.getTelegramId() != null) {
            try {
                boolean sent = telegramBotService.sendMessage(user.getTelegramId(), format(title, message));
                if (sent) {
                    notification.setSentViaTelegram(true);
                    notificationRepository.save(notification);
                }
            } catch (Exception ex) {
                // A failed push must not fail the notification (user may have blocked the bot).
                log.warn("Telegram push failed for user {}: {}", userId, ex.toString());
            }
        }
        return notification;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(Long userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(notificationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public int getUnreadCount(Long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!notification.getUserId().equals(userId)) {
            throw new ForbiddenException("This notification does not belong to you");
        }
        notification.setRead(true);
        notificationRepository.save(notification);
    }

    @Transactional
    public void markAllAsRead(Long userId) {
        notificationRepository.markAllAsRead(userId);
    }

    private String format(String title, String message) {
        return "<b>" + title + "</b>\n" + message;
    }
}
