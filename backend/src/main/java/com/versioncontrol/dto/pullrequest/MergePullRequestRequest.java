package com.versioncontrol.dto.pullrequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MergePullRequestRequest(
        @NotNull MergeStrategy strategy,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedSourceHead,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedTargetHead,
        @NotBlank @Size(max = 500) String commitMessage) { }
