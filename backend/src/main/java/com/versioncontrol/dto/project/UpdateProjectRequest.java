package com.versioncontrol.dto.project;

import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(@Size(min = 1, max = 120) String name, @Size(max = 10000) String description) { }
