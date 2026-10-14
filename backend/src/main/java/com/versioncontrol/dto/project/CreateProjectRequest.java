package com.versioncontrol.dto.project;

import com.versioncontrol.entity.ProjectScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(@NotBlank @Size(max = 120) String name, @Size(max = 10000) String description,
        ProjectScope scope, Long repositoryId) { }
