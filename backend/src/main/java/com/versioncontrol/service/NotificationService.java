package com.versioncontrol.service;

import com.versioncontrol.dto.notification.NotificationPageResponse;
import com.versioncontrol.dto.notification.NotificationResponse;
import com.versioncontrol.entity.NotificationEventType;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.User;
import com.versioncontrol.entity.UserNotification;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.RepositoryCollaboratorRepository;
import com.versioncontrol.repository.UserNotificationRepository;
import com.versioncontrol.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final UserNotificationRepository notifications;
    private final UserRepository users;
    private final RepositoryCollaboratorRepository collaborators;
    private final RepositoryAccessService access;

    public NotificationService(UserNotificationRepository notifications, UserRepository users,
            RepositoryCollaboratorRepository collaborators, RepositoryAccessService access) {
        this.notifications = notifications; this.users = users; this.collaborators = collaborators; this.access = access;
    }

    @Transactional
    public void notifyRecipients(Iterable<Long> recipientIds, Long actorId, Repository repository,
            NotificationEventType type, String title, String message, String targetType, Long targetId, String key) {
        Set<Long> unique = new LinkedHashSet<>();
        recipientIds.forEach(unique::add);
        unique.remove(null);
        unique.remove(actorId);
        if (unique.isEmpty()) return;
        if (repository != null && repository.getVisibility() == com.versioncontrol.entity.RepositoryVisibility.PRIVATE) {
            Set<Long> readable = new LinkedHashSet<>(collaborators.acceptedUserIds(repository.getId(), List.copyOf(unique)));
            readable.add(repository.getOwner().getId());
            unique.retainAll(readable);
        }
        if (unique.isEmpty()) return;
        List<Long> ids = List.copyOf(unique);
        Set<Long> alreadyNotified = new LinkedHashSet<>(notifications.findRecipientIdsWithKey(ids, key));
        Map<Long, User> usersById = new HashMap<>();
        users.findAllById(ids).forEach(user -> usersById.put(user.getId(), user));
        User actor = actorId == null ? null : users.findById(actorId).orElse(null);
        List<UserNotification> pending = new ArrayList<>();
        for (Long recipientId : ids) {
            User recipient = usersById.get(recipientId);
            if (recipient == null || alreadyNotified.contains(recipientId)) continue;
            pending.add(new UserNotification(recipient, actor, repository, type,
                    safe(title, 255), safe(message, 500), safe(targetType, 40), targetId, key));
        }
        notifications.saveAll(pending);
    }

    @Transactional(readOnly = true)
    public NotificationPageResponse list(Long userId, Boolean unread, int page, int size) {
        validatePage(page, size);
        List<Long> repositoryIds = collaboratorRepositoryIds(userId);
        Page<UserNotification> result = notifications.findReadable(userId, unread, repositoryIds,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new NotificationPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.hasNext(), result.getContent().stream().map(NotificationResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) { return notifications.countReadableUnread(userId, collaboratorRepositoryIds(userId)); }

    @Transactional
    public NotificationResponse mark(Long id, Long userId, boolean read) {
        UserNotification notification = notifications.findOwned(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (notification.getRepository() != null) access.requireReadable(notification.getRepository(), userId);
        notification.setReadAt(read ? notification.getReadAt() == null ? LocalDateTime.now() : notification.getReadAt() : null);
        return NotificationResponse.from(notifications.save(notification));
    }

    @Transactional
    public int markAllRead(Long userId) {
        List<Long> repositoryIds = collaboratorRepositoryIds(userId);
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Page<UserNotification> first = notifications.findReadable(userId, true, repositoryIds, PageRequest.of(0, 100, sort));
        int changed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (int page = first.getTotalPages() - 1; page >= 0; page--) {
            Page<UserNotification> batch = page == 0 ? first : notifications.findReadable(userId, true, repositoryIds,
                    PageRequest.of(page, 100, sort));
            for (UserNotification notification : batch.getContent()) {
                if (notification.getRepository() != null) access.requireReadable(notification.getRepository(), userId);
                notification.setReadAt(now);
            }
            notifications.saveAll(batch.getContent());
            notifications.flush();
            changed += batch.getNumberOfElements();
        }
        return changed;
    }

    private List<Long> collaboratorRepositoryIds(Long userId) {
        List<Long> ids = collaborators.acceptedRepositoryIds(userId);
        return ids.isEmpty() ? List.of(-1L) : ids;
    }
    private void validatePage(int page, int size) {
        if (page < 0 || page > 2_000 || size < 1 || size > 100)
            throw new InvalidRequestException("Page must be between 0 and 2000 and size between 1 and 100");
    }
    private String safe(String value, int max) {
        if (value == null || value.isBlank()) throw new InvalidRequestException("Notification fields are required");
        String cleaned = value.trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
