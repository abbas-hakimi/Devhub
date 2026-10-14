package com.versioncontrol.service;

import com.versioncontrol.dto.auth.AuthResponse;
import java.time.LocalDateTime;

public record RefreshRotationResult(boolean successful, AuthResponse response,
        String replacementToken, LocalDateTime expiresAt) {
    public static RefreshRotationResult invalid() {
        return new RefreshRotationResult(false, null, null, null);
    }
}
