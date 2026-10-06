package com.versioncontrol.dto.label;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLabelRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 255) String description,
        @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color
) { }
