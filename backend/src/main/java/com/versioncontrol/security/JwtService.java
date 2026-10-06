package com.versioncontrol.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration expiration;

    public JwtService(
            @Value("${jwt.secret}") String base64Secret,
            @Value("${jwt.expiration:PT15M}") Duration expiration
    ) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        if (expiration.isZero() || expiration.isNegative()) {
            throw new IllegalArgumentException("JWT expiration must be positive.");
        }
        this.expiration = expiration;
    }

    public String generateAccessToken(UserPrincipal user) {
        Instant issuedAt = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(expiration)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValidForUser(Claims claims, UserPrincipal user) {
        Date issuedAt = claims.getIssuedAt();
        Date expiresAt = claims.getExpiration();
        Instant now = Instant.now();
        return claims.getSubject() != null
                && claims.getSubject().equals(user.getId().toString())
                && issuedAt != null
                && !issuedAt.toInstant().isAfter(now.plusSeconds(60))
                && expiresAt != null
                && expiresAt.toInstant().isAfter(now);
    }

    public long getExpirationSeconds() {
        return expiration.toSeconds();
    }
}

