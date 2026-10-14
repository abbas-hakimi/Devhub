package com.versioncontrol.controller;

import com.versioncontrol.dto.notification.NotificationPageResponse;
import com.versioncontrol.dto.notification.NotificationResponse;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    public NotificationPageResponse list(@RequestParam(required = false) Boolean unread,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return notifications.list(principal.getId(), unread, page, size);
    }
    @GetMapping("/unread-count")
    public long unreadCount(@AuthenticationPrincipal UserPrincipal principal) { return notifications.unreadCount(principal.getId()); }
    @PatchMapping("/{notificationId}/read")
    public NotificationResponse read(@PathVariable Long notificationId, @AuthenticationPrincipal UserPrincipal principal) {
        return notifications.mark(notificationId, principal.getId(), true);
    }
    @PatchMapping("/{notificationId}/unread")
    public NotificationResponse unread(@PathVariable Long notificationId, @AuthenticationPrincipal UserPrincipal principal) {
        return notifications.mark(notificationId, principal.getId(), false);
    }
    @PostMapping("/mark-all-read")
    public MarkAllReadResponse markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        return new MarkAllReadResponse(notifications.markAllRead(principal.getId()));
    }
    public record MarkAllReadResponse(int markedRead) { }
}
