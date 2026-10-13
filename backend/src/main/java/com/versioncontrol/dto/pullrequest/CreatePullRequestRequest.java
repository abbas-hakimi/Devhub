package com.versioncontrol.dto.pullrequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePullRequestRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 16000) String description,
        @NotBlank @Size(max = 255) String sourceBranch,
        @NotBlank @Size(max = 255) String targetBranch) { }
