package com.versioncontrol.dto.project;

import jakarta.validation.constraints.Size;
public record UpdateProjectCardRequest(@Size(min = 1, max = 255) String title, @Size(max = 10000) String description) { }
