package com.versioncontrol.dto.issue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateIssueRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 16000) String description,
        Long assigneeId
) { }
