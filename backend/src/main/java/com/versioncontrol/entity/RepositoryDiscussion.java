package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "repository_discussions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_discussions_repository_number", columnNames = {"repository_id", "discussion_number"}),
        @UniqueConstraint(name = "uk_discussions_repository_id_id", columnNames = {"repository_id", "id"})
}, indexes = {
        @Index(name = "idx_discussions_repository_state_number", columnList = "repository_id,state,discussion_number"),
        @Index(name = "idx_discussions_repository_created", columnList = "repository_id,created_at,id")
})
public class RepositoryDiscussion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;
    @Column(name = "discussion_number", nullable = false)
    private Long number;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private DiscussionCategory category;
    @Column(nullable = false, length = 255)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id", nullable = false)
    private User author;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private DiscussionState state = DiscussionState.OPEN;
    @Column(name = "accepted_reply_id")
    private Long acceptedReplyId;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    protected RepositoryDiscussion() { }
    public RepositoryDiscussion(Repository repository, Long number, DiscussionCategory category, String title, String body, User author) {
        this.repository = repository; this.number = number; this.category = category; this.title = title; this.body = body; this.author = author;
    }
    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public Long getNumber() { return number; }
    public DiscussionCategory getCategory() { return category; }
    public void setCategory(DiscussionCategory category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public User getAuthor() { return author; }
    public DiscussionState getState() { return state; }
    public void setState(DiscussionState state) { this.state = state; }
    public Long getAcceptedReplyId() { return acceptedReplyId; }
    public void setAcceptedReplyId(Long acceptedReplyId) { this.acceptedReplyId = acceptedReplyId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
}
