package com.versioncontrol.service;

import com.versioncontrol.dto.auth.AuthResponse;
import com.versioncontrol.dto.auth.SessionResponse;
import com.versioncontrol.dto.user.UserResponse;
import com.versioncontrol.entity.RefreshSession;
import com.versioncontrol.entity.RefreshSessionRevocationReason;
import com.versioncontrol.entity.RefreshTokenHistory;
import com.versioncontrol.entity.User;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.RefreshSessionRepository;
import com.versioncontrol.repository.RefreshTokenHistoryRepository;
import com.versioncontrol.repository.UserRepository;
import com.versioncontrol.security.JwtService;
import com.versioncontrol.security.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshSessionService {
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshSessionRepository sessions;
    private final RefreshTokenHistoryRepository tokens;
    private final UserRepository users;
    private final JwtService jwtService;
    private final Duration refreshLifetime;
    private final Duration replayRetention;

    public RefreshSessionService(RefreshSessionRepository sessions, RefreshTokenHistoryRepository tokens,
            UserRepository users, JwtService jwtService,
            @Value("${auth.refresh-token-ttl:PT720H}") Duration refreshLifetime,
            @Value("${auth.refresh-token-replay-retention:PT720H}") Duration replayRetention) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.users = users;
        this.jwtService = jwtService;
        if (refreshLifetime.isZero() || refreshLifetime.isNegative()
                || replayRetention.isZero() || replayRetention.isNegative()) {
            throw new IllegalArgumentException("Refresh-token lifetime and replay retention must be positive");
        }
        this.refreshLifetime = refreshLifetime;
        this.replayRetention = replayRetention;
    }

    @Transactional
    public IssuedToken createSession(Long userId, String userAgent) {
        User user = users.lockById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        LocalDateTime now = now();
        LocalDateTime expiresAt = now.plus(refreshLifetime);
        RefreshSession session = sessions.saveAndFlush(new RefreshSession(user, UUID.randomUUID().toString(),
                expiresAt, expiresAt.plus(replayRetention), now, boundedUserAgent(userAgent)));
        String rawToken = randomToken();
        tokens.saveAndFlush(new RefreshTokenHistory(session, tokenHash(rawToken), 0, expiresAt));
        return new IssuedToken(rawToken, expiresAt);
    }

    /**
     * Serializes refresh, logout, and revocation by locking the session before its token-history row.
     * Failure results are returned instead of thrown so a detected replay can commit family revocation.
     */
    @Transactional
    public RefreshRotationResult rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) return RefreshRotationResult.invalid();
        String hash = tokenHash(rawToken);
        Long sessionId = tokens.findSessionIdByTokenHash(hash).orElse(null);
        if (sessionId == null) return RefreshRotationResult.invalid();
        RefreshSession session = sessions.lockById(sessionId).orElse(null);
        if (session == null) return RefreshRotationResult.invalid();
        RefreshTokenHistory previous = tokens.lockByTokenHash(hash).orElse(null);
        if (previous == null || !previous.getSession().getId().equals(sessionId)) return RefreshRotationResult.invalid();

        LocalDateTime now = now();
        if (session.getRevokedAt() != null) return RefreshRotationResult.invalid();
        if (previous.isUsed()) {
            revoke(session, now, RefreshSessionRevocationReason.TOKEN_REPLAY);
            return RefreshRotationResult.invalid();
        }
        if (session.getExpiresAt().compareTo(now) <= 0 || previous.getExpiresAt().compareTo(now) <= 0) {
            revoke(session, now, RefreshSessionRevocationReason.EXPIRED);
            return RefreshRotationResult.invalid();
        }
        if (previous.isRevoked()) return RefreshRotationResult.invalid();

        String replacement = randomToken();
        Integer generation = tokens.findMaximumGeneration(sessionId);
        previous.consume(now);
        tokens.save(previous);
        tokens.saveAndFlush(new RefreshTokenHistory(session, tokenHash(replacement),
                generation == null ? 1 : generation + 1, session.getExpiresAt()));
        session.setLastUsedAt(now);

        User user = session.getUser();
        UserPrincipal principal = UserPrincipal.from(user);
        AuthResponse response = new AuthResponse(jwtService.generateAccessToken(principal), "Bearer",
                jwtService.getExpirationSeconds(), UserResponse.from(user));
        return new RefreshRotationResult(true, response, replacement, session.getExpiresAt());
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) return;
        String hash = tokenHash(rawToken);
        Long sessionId = tokens.findSessionIdByTokenHash(hash).orElse(null);
        if (sessionId == null) return;
        RefreshSession session = sessions.lockById(sessionId).orElse(null);
        if (session != null && session.getRevokedAt() == null) {
            revoke(session, now(), RefreshSessionRevocationReason.USER_LOGOUT);
        }
    }

    @Transactional
    public void logoutAll(Long userId) {
        users.lockById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        LocalDateTime now = now();
        for (RefreshSession session : sessions.lockAllForUser(userId)) {
            if (session.getRevokedAt() == null) revoke(session, now, RefreshSessionRevocationReason.LOGOUT_ALL);
        }
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(Long userId) {
        return sessions.listForUser(userId).stream().map(SessionResponse::from).toList();
    }

    @Transactional
    public boolean revokeSession(Long sessionId, Long userId) {
        RefreshSession session = sessions.lockByIdAndUserId(sessionId, userId).orElse(null);
        if (session == null) return false;
        if (session.getRevokedAt() == null) revoke(session, now(), RefreshSessionRevocationReason.USER_REVOKED);
        return true;
    }

    @Transactional
    public int purgeExpiredHistory() {
        return sessions.deleteExpiredHistory(now());
    }

    private void revoke(RefreshSession session, LocalDateTime at, RefreshSessionRevocationReason reason) {
        session.revoke(at, reason);
        tokens.revokeAllForSession(session.getId(), at);
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String boundedUserAgent(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() <= 255 ? clean : clean.substring(0, 255);
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    public record IssuedToken(String token, LocalDateTime expiresAt) { }
}
