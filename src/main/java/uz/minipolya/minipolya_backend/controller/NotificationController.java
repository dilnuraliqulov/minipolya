package uz.minipolya.minipolya_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.NotificationResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.UnreadCountResponse;
import uz.minipolya.minipolya_backend.service.NotificationService;

import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/api/notifications")
    public ApiResponse<PageResponse<NotificationResponse>> myNotifications(@AuthenticationPrincipal Long userId,
                                                                           @RequestParam(defaultValue = "0") int page,
                                                                           @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                notificationService.getNotifications(userId, pageable), Function.identity()));
    }

    @GetMapping("/api/notifications/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(UnreadCountResponse.builder()
                .count(notificationService.getUnreadCount(userId)).build());
    }

    @PutMapping("/api/notifications/{id}/read")
    public ApiResponse<Void> markRead(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        notificationService.markAsRead(id, userId);
        return ApiResponse.ok(null, "Marked as read");
    }

    @PutMapping("/api/notifications/read-all")
    public ApiResponse<Void> markAllRead(@AuthenticationPrincipal Long userId) {
        notificationService.markAllAsRead(userId);
        return ApiResponse.ok(null, "All marked as read");
    }
}
