package com.versioncontrol.dto.auth;

import com.versioncontrol.dto.user.UserResponse;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserResponse user
) {
}

