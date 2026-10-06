package com.versioncontrol.dto.user;

import java.time.LocalDateTime;
import com.versioncontrol.entity.User;

public record UserResponse(Long id, String username, String email, String displayName, String bio, String avatarUrl, LocalDateTime createdAt) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(), user.getBio(), user.getAvatarUrl(), user.getCreatedAt());
    }
}
