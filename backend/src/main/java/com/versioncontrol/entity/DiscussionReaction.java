package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "discussion_reactions", uniqueConstraints = @UniqueConstraint(
        name = "uk_discussion_reaction_user_type", columnNames = {"discussion_id", "user_id", "reaction_type"}),
        indexes = @Index(name = "idx_discussion_reactions_counts", columnList = "discussion_id,reaction_type"))
public class DiscussionReaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "discussion_id", nullable = false) private RepositoryDiscussion discussion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Enumerated(EnumType.STRING) @Column(name = "reaction_type", nullable = false, length = 24) private DiscussionReactionType type;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    protected DiscussionReaction() { }
    public DiscussionReaction(RepositoryDiscussion discussion, User user, DiscussionReactionType type) { this.discussion = discussion; this.user = user; this.type = type; }
    public Long getId() { return id; }
    public RepositoryDiscussion getDiscussion() { return discussion; }
    public User getUser() { return user; }
    public DiscussionReactionType getType() { return type; }
}
