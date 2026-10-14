package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "discussion_replies", uniqueConstraints = @UniqueConstraint(
        name = "uk_discussion_replies_discussion_id", columnNames = {"discussion_id", "id"}), indexes = {
        @Index(name = "idx_discussion_replies_discussion_created", columnList = "discussion_id,created_at,id"),
        @Index(name = "idx_discussion_replies_parent", columnList = "discussion_id,parent_reply_id")
})
public class DiscussionReply {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "discussion_id", nullable = false)
    private RepositoryDiscussion discussion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id", nullable = false)
    private User author;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_reply_id")
    private DiscussionReply parent;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DiscussionReply() { }
    public DiscussionReply(RepositoryDiscussion discussion, User author, DiscussionReply parent, String body) {
        this.discussion = discussion; this.author = author; this.parent = parent; this.body = body;
    }
    public Long getId() { return id; }
    public RepositoryDiscussion getDiscussion() { return discussion; }
    public User getAuthor() { return author; }
    public DiscussionReply getParent() { return parent; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
