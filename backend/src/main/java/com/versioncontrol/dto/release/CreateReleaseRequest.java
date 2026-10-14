package com.versioncontrol.dto.release;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateReleaseRequest(
        @NotBlank @Size(max = 255) String tagName,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 16000) String description,
        Boolean draft,
        Boolean prerelease) { }
