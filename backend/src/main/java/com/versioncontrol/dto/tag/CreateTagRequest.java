package com.versioncontrol.dto.tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTagRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be a 64-character lowercase SHA-256 hash") String commitHash) { }
