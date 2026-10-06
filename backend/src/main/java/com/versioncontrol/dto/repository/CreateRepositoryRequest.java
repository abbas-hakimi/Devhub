package com.versioncontrol.dto.repository;

import com.versioncontrol.entity.RepositoryVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRepositoryRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 10000) String description,
        @NotNull RepositoryVisibility visibility
) { }
