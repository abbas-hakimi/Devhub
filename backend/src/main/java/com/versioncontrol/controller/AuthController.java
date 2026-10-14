package com.versioncontrol.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.versioncontrol.dto.auth.AuthResponse;
import com.versioncontrol.dto.auth.LoginRequest;
import com.versioncontrol.dto.auth.RegisterRequest;
import com.versioncontrol.dto.auth.SessionResponse;
import com.versioncontrol.dto.ApiError;
import com.versioncontrol.dto.user.UserResponse;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.AuthenticationService;
import com.versioncontrol.service.LoginSessionResult;
import com.versioncontrol.service.RefreshCookieFactory;
import com.versioncontrol.service.RefreshRotationResult;
import com.versioncontrol.service.RefreshSessionService;
import com.versioncontrol.security.TrustedOriginPolicy;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationService authenticationService;
    private final RefreshSessionService refreshSessions;
    private final RefreshCookieFactory cookies;
    private final TrustedOriginPolicy origins;
    public AuthController(AuthenticationService authenticationService, RefreshSessionService refreshSessions,
            RefreshCookieFactory cookies, TrustedOriginPolicy origins) {
        this.authenticationService = authenticationService;
        this.refreshSessions = refreshSessions;
        this.cookies = cookies;
        this.origins = origins;
    }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) { return authenticationService.register(request); }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        LoginSessionResult result = authenticationService.login(request, servletRequest.getHeader("User-Agent"));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                cookies.issue(result.refreshToken(), remaining(result.expiresAt())).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(result.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        origins.requireTrustedRequest(request);
        RefreshRotationResult result = refreshSessions.rotate(cookieValue(request));
        if (!result.successful()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new ApiError(Instant.now(), 401, "Refresh session is invalid", Map.of()));
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                cookies.issue(result.replacementToken(), remaining(result.expiresAt())).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(result.response());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        origins.requireTrustedRequest(request);
        refreshSessions.logout(cookieValue(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request) {
        origins.requireTrustedRequest(request);
        refreshSessions.logoutAll(principal.getId());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal UserPrincipal principal) {
        return refreshSessions.listSessions(principal.getId());
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable Long sessionId, @AuthenticationPrincipal UserPrincipal principal) {
        if (!refreshSessions.revokeSession(sessionId, principal.getId())) {
            throw new com.versioncontrol.exception.ResourceNotFoundException("Session not found");
        }
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return authenticationService.currentUser(principal.getId());
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] requestCookies = request.getCookies();
        if (requestCookies == null) return null;
        for (Cookie cookie : requestCookies) if (cookies.name().equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private Duration remaining(LocalDateTime expiresAt) {
        return Duration.between(LocalDateTime.now(ZoneOffset.UTC), expiresAt).truncatedTo(ChronoUnit.SECONDS);
    }
}
