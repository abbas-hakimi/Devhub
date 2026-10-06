package com.versioncontrol.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Index;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "user_follows",
        indexes = @Index(name = "idx_user_follows_following_id", columnList = "following_id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_follows_follower_following",
                columnNames = {"follower_id", "following_id"}
        )
)
public class UserFollow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "follower_id", nullable = false)
    private User follower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "following_id", nullable = false)
    private User following;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected UserFollow() {
    }

    public UserFollow(User follower, User following) {
        this.follower = follower;
        this.following = following;
        ensureNotSelfFollow();
    }

    @PrePersist
    @PreUpdate
    private void validateFollow() {
        ensureNotSelfFollow();
    }

    private void ensureNotSelfFollow() {
        if (follower == null || following == null) {
            return;
        }
        if (follower == following
                || (follower.getId() != null && follower.getId().equals(following.getId()))) {
            throw new IllegalArgumentException("A user cannot follow themselves.");
        }
    }

    public Long getId() {
        return id;
    }

    public User getFollower() {
        return follower;
    }

    public void setFollower(User follower) {
        this.follower = follower;
    }

    public User getFollowing() {
        return following;
    }

    public void setFollowing(User following) {
        this.following = following;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

}

