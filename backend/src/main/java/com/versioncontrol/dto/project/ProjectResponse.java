package com.versioncontrol.dto.project;

import com.versioncontrol.entity.Project;
import com.versioncontrol.entity.ProjectStatus;
import com.versioncontrol.entity.ProjectScope;
import com.versioncontrol.entity.RepositoryVisibility;
import java.time.LocalDateTime;

public record ProjectResponse(Long id, String name, String description, ProjectScope scope, Long ownerId,
        Long repositoryId, String repositoryName, RepositoryVisibility visibility, ProjectStatus status,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static ProjectResponse from(Project p) {
        RepositoryVisibility visibility = p.getScope() == ProjectScope.PERSONAL ? p.getVisibility() : p.getRepository().getVisibility();
        return new ProjectResponse(p.getId(), p.getName(), p.getDescription(), p.getScope(), p.getOwner().getId(),
                p.getRepository() == null ? null : p.getRepository().getId(),
                p.getRepository() == null ? null : p.getRepository().getName(), visibility, p.getStatus(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
