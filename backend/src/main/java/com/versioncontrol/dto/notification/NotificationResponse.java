package com.versioncontrol.dto.notification;

import com.versioncontrol.entity.UserNotification;
import java.time.LocalDateTime;

public record NotificationResponse(Long id, String eventType, String title, String message, Long repositoryId,
        String targetType, Long targetId, Long actorId, String actorUsername, LocalDateTime createdAt, LocalDateTime readAt) {
    public static NotificationResponse from(UserNotification n) {
        return new NotificationResponse(n.getId(), n.getEventType().name(), n.getTitle(), n.getMessage(),
                n.getRepository() == null ? null : n.getRepository().getId(), n.getTargetType(), n.getTargetId(),
                n.getActor() == null ? null : n.getActor().getId(), n.getActor() == null ? null : n.getActor().getUsername(),
                n.getCreatedAt(), n.getReadAt());
    }
}
