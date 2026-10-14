package com.versioncontrol.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "refresh_token_history", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refresh_token_history_hash", columnNames = "token_hash"),
        @UniqueConstraint(name = "uk_refresh_token_history_generation", columnNames = {"session_id", "generation"})
})
public class RefreshTokenHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private RefreshSession session;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(nullable = false)
    private int generation;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected RefreshTokenHistory() { }

    public RefreshTokenHistory(RefreshSession session, String tokenHash, int generation,
            LocalDateTime expiresAt) {
        this.session = session;
        this.tokenHash = tokenHash;
        this.generation = generation;
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() { return usedAt != null; }
    public boolean isRevoked() { return revokedAt != null; }
    public void consume(LocalDateTime at) { usedAt = at; }
    public void revoke(LocalDateTime at) { if (revokedAt == null) revokedAt = at; }

    public Long getId() { return id; }
    public RefreshSession getSession() { return session; }
    public String getTokenHash() { return tokenHash; }
    public int getGeneration() { return generation; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
}
