package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "discussion_reply_reactions", uniqueConstraints = @UniqueConstraint(
        name = "uk_discussion_reply_reaction_user_type", columnNames = {"reply_id", "user_id", "reaction_type"}),
        indexes = @Index(name = "idx_discussion_reply_reactions_counts", columnList = "reply_id,reaction_type"))
public class DiscussionReplyReaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "reply_id", nullable = false) private DiscussionReply reply;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Enumerated(EnumType.STRING) @Column(name = "reaction_type", nullable = false, length = 24) private DiscussionReactionType type;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    protected DiscussionReplyReaction() { }
    public DiscussionReplyReaction(DiscussionReply reply, User user, DiscussionReactionType type) { this.reply = reply; this.user = user; this.type = type; }
    public Long getId() { return id; }
    public DiscussionReply getReply() { return reply; }
    public User getUser() { return user; }
    public DiscussionReactionType getType() { return type; }
}
