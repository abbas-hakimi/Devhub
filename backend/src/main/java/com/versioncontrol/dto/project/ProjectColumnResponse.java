package com.versioncontrol.dto.project;

import com.versioncontrol.entity.ProjectColumn;
import java.time.LocalDateTime;
public record ProjectColumnResponse(Long id, Long projectId, String name, int position, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static ProjectColumnResponse from(ProjectColumn c) { return new ProjectColumnResponse(c.getId(), c.getProject().getId(), c.getName(), c.getPosition(), c.getCreatedAt(), c.getUpdatedAt()); }
}
