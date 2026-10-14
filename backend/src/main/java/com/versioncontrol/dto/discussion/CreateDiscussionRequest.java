package com.versioncontrol.dto.discussion;

import com.versioncontrol.entity.DiscussionCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateDiscussionRequest(@NotNull DiscussionCategory category, @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 64000) String body) { }
