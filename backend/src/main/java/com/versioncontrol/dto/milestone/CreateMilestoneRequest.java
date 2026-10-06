package com.versioncontrol.dto.milestone;

import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMilestoneRequest(
        @NotBlank @Size(max = 100) String title,
        @Size(max = 16000) String description,
        LocalDate dueDate
) { }
