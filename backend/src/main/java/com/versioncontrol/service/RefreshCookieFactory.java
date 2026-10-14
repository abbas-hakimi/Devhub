package com.versioncontrol.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookieFactory {
    private final String name;
    private final String path;
    private final String sameSite;
    private final boolean secure;

    public RefreshCookieFactory(@Value("${auth.cookie.name:DEVHUB_REFRESH}") String name,
            @Value("${auth.cookie.path:/api/auth}") String path,
            @Value("${auth.cookie.same-site:Lax}") String sameSite,
            @Value("${auth.cookie.secure:false}") boolean secure) {
        this.name = name;
        this.path = path;
        this.sameSite = sameSite;
        this.secure = secure;
        if (!"Lax".equalsIgnoreCase(sameSite) && !"Strict".equalsIgnoreCase(sameSite)
                && !"None".equalsIgnoreCase(sameSite)) {
            throw new IllegalArgumentException("auth.cookie.same-site must be Lax, Strict, or None");
        }
        if ("None".equalsIgnoreCase(sameSite) && !secure) {
            throw new IllegalArgumentException("SameSite=None requires a Secure refresh cookie");
        }
        if (!path.startsWith("/")) throw new IllegalArgumentException("Refresh cookie path must be absolute");
    }

    public String name() { return name; }

    public ResponseCookie issue(String value, Duration maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite(sameSite)
                .path(path).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
    }

    public ResponseCookie clear() {
        return ResponseCookie.from(name, "").httpOnly(true).secure(secure).sameSite(sameSite)
                .path(path).maxAge(Duration.ZERO).build();
    }
}
