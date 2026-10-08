package com.versioncontrol.dto.git;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCommitRequest(
        @NotBlank @Size(max = 255) String branch,
        @Pattern(regexp = "[0-9a-f]{64}") String expectedHeadHash,
        @NotBlank @Size(max = 2000) String message,
        @Size(max = 25) List<@jakarta.validation.constraints.NotNull @Valid CommitChangeRequest> changes,
        @Size(max = 25) List<@NotBlank @Size(max = 4096) String> deletedPaths
) { }
