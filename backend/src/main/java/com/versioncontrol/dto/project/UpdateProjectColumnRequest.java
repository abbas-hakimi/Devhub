package com.versioncontrol.dto.project;

import jakarta.validation.constraints.Size;
public record UpdateProjectColumnRequest(@Size(min = 1, max = 80) String name) { }
