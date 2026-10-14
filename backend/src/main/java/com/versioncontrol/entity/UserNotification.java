package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "user_notifications")
public class UserNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_id")
    private User actor;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "repository_id")
    private Repository repository;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 48)
    private NotificationEventType eventType;
    @Column(nullable = false, length = 255) private String title;
    @Column(nullable = false, length = 500) private String message;
    @Column(name = "target_type", nullable = false, length = 40) private String targetType;
    @Column(name = "target_id", nullable = false) private Long targetId;
    @Column(name = "deduplication_key", length = 190) private String deduplicationKey;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "read_at") private LocalDateTime readAt;

    protected UserNotification() { }
    public UserNotification(User recipient, User actor, Repository repository, NotificationEventType eventType,
            String title, String message, String targetType, Long targetId, String deduplicationKey) {
        this.recipient = recipient; this.actor = actor; this.repository = repository; this.eventType = eventType;
        this.title = title; this.message = message; this.targetType = targetType; this.targetId = targetId;
        this.deduplicationKey = deduplicationKey;
    }
    public Long getId() { return id; }
    public User getRecipient() { return recipient; }
    public User getActor() { return actor; }
    public Repository getRepository() { return repository; }
    public NotificationEventType getEventType() { return eventType; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
}
