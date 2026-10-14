package com.versioncontrol.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "refresh_sessions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refresh_sessions_family", columnNames = "token_family_id")
})
public class RefreshSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_family_id", nullable = false, length = 36, unique = true, columnDefinition = "CHAR(36)")
    private String tokenFamilyId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "retained_until", nullable = false)
    private LocalDateTime retainedUntil;

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 40)
    private RefreshSessionRevocationReason revocationReason;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    protected RefreshSession() { }

    public RefreshSession(User user, String tokenFamilyId, LocalDateTime expiresAt,
            LocalDateTime retainedUntil, LocalDateTime lastUsedAt, String userAgent) {
        this.user = user;
        this.tokenFamilyId = tokenFamilyId;
        this.expiresAt = expiresAt;
        this.retainedUntil = retainedUntil;
        this.lastUsedAt = lastUsedAt;
        this.userAgent = userAgent;
    }

    public void setLastUsedAt(LocalDateTime value) { this.lastUsedAt = value; }

    public void revoke(LocalDateTime at, RefreshSessionRevocationReason reason) {
        if (revokedAt == null) {
            revokedAt = at;
            revocationReason = reason;
        }
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTokenFamilyId() { return tokenFamilyId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRetainedUntil() { return retainedUntil; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public RefreshSessionRevocationReason getRevocationReason() { return revocationReason; }
    public String getUserAgent() { return userAgent; }
}
