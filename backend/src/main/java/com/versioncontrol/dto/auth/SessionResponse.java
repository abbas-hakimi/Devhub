package com.versioncontrol.dto.auth;

import com.versioncontrol.entity.RefreshSession;
import com.versioncontrol.entity.RefreshSessionRevocationReason;
import java.time.LocalDateTime;

public record SessionResponse(Long sessionId, LocalDateTime createdAt, LocalDateTime expiresAt,
        LocalDateTime lastActivityAt, LocalDateTime revokedAt,
        RefreshSessionRevocationReason revocationReason, String userAgent) {
    public static SessionResponse from(RefreshSession session) {
        return new SessionResponse(session.getId(), session.getCreatedAt(), session.getExpiresAt(),
                session.getLastUsedAt(), session.getRevokedAt(), session.getRevocationReason(), session.getUserAgent());
    }
}
