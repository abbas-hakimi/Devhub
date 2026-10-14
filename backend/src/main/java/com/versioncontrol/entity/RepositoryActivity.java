package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "repository_activities")
public class RepositoryActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_id") private User actor;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 48) private RepositoryActivityEventType eventType;
    @Column(name = "resource_type", nullable = false, length = 40) private String resourceType;
    @Column(name = "resource_id", nullable = false) private Long resourceId;
    @Column(nullable = false, length = 500) private String summary;
    @Column(name = "deduplication_key", length = 190) private String deduplicationKey;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    protected RepositoryActivity() { }
    public RepositoryActivity(Repository repository, User actor, RepositoryActivityEventType eventType,
            String resourceType, Long resourceId, String summary, String deduplicationKey) {
        this.repository = repository; this.actor = actor; this.eventType = eventType; this.resourceType = resourceType;
        this.resourceId = resourceId; this.summary = summary; this.deduplicationKey = deduplicationKey;
    }
    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public User getActor() { return actor; }
    public RepositoryActivityEventType getEventType() { return eventType; }
    public String getResourceType() { return resourceType; }
    public Long getResourceId() { return resourceId; }
    public String getSummary() { return summary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
