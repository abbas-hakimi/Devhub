package com.versioncontrol.dto.git;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.versioncontrol.validation.ValidBranchName;

public record CreateBranchRequest(
        @NotBlank @ValidBranchName String name,
        @Pattern(regexp = "[0-9a-f]{64}") String fromCommitHash
) { }
