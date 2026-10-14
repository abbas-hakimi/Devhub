package com.versioncontrol.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record CreateProjectColumnRequest(@NotBlank @Size(max = 80) String name) { }
