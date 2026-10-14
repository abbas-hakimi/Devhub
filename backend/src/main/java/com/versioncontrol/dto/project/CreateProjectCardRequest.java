package com.versioncontrol.dto.project;

import com.versioncontrol.entity.ProjectCardType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
public record CreateProjectCardRequest(@NotNull Long columnId, @NotNull ProjectCardType cardType,
        @Size(min = 1, max = 255) String title, @Size(max = 10000) String description, Long issueId, Long pullRequestId) { }
