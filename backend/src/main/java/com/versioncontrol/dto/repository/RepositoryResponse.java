package com.versioncontrol.dto.repository;

import java.time.LocalDateTime;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryVisibility;

public record RepositoryResponse(
        Long id,
        String name,
        String description,
        RepositoryVisibility visibility,
        OwnerResponse owner,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RepositoryResponse from(Repository repository) {
        var owner = repository.getOwner();
        return new RepositoryResponse(repository.getId(), repository.getName(), repository.getDescription(), repository.getVisibility(),
                new OwnerResponse(owner.getId(), owner.getUsername(), owner.getDisplayName()), repository.getCreatedAt(), repository.getUpdatedAt());
    }

    public record OwnerResponse(Long id, String username, String displayName) { }
}
