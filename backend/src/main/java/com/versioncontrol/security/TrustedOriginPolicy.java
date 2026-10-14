package com.versioncontrol.security;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletRequest;
import com.versioncontrol.exception.ResourceForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TrustedOriginPolicy {
    private final Set<String> trustedOrigins;

    public TrustedOriginPolicy(@Value("${auth.frontend-origins:http://localhost:3000}") String origins) {
        this.trustedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(this::normalizeConfiguredOrigin)
                .collect(Collectors.toUnmodifiableSet());
        if (trustedOrigins.isEmpty()) throw new IllegalArgumentException("At least one frontend origin must be configured");
    }

    public Set<String> origins() { return trustedOrigins; }

    public boolean isTrusted(String candidate) {
        if (candidate == null || candidate.isBlank() || "null".equalsIgnoreCase(candidate.trim())) return false;
        try {
            URI uri = URI.create(candidate.trim());
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) return false;
            return trustedOrigins.contains(origin(uri));
        }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public void requireTrustedRequest(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        boolean trusted = origin != null ? isTrusted(origin) : trustedReferer(request.getHeader("Referer"));
        if (!trusted) throw new ResourceForbiddenException("Request origin is not allowed");
    }

    private boolean trustedReferer(String referer) {
        if (referer == null || referer.isBlank()) return false;
        try {
            URI uri = URI.create(referer);
            return trustedOrigins.contains(origin(uri));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private String normalizeConfiguredOrigin(String value) {
        if (value.contains("*") || value.contains(",")) throw new IllegalArgumentException("Frontend origins must be explicit origins");
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid configured frontend origin", invalid); }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Frontend configuration values must be origins without paths or credentials");
        }
        return origin(uri);
    }

    private String origin(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Origin must use HTTP or HTTPS and include a host");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
        return scheme + "://" + host + (port < 0 ? "" : ":" + port);
    }
}
