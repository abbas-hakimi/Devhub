package com.versioncontrol.service;

import org.springframework.stereotype.Service;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryVisibility;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.RepositoryRepository;

@Service
public class RepositoryAccessService {
    private final RepositoryRepository repositories;
    public RepositoryAccessService(RepositoryRepository repositories) { this.repositories = repositories; }

    public Repository find(Long id) {
        return repositories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
    }

    public void requireReadable(Repository repository, Long userId) {
        if (repository.getVisibility() == RepositoryVisibility.PRIVATE && !isOwner(repository, userId)) {
            throw new ResourceNotFoundException("Resource not found");
        }
    }

    public void requireOwner(Repository repository, Long userId) {
        if (!isOwner(repository, userId)) throw new ResourceForbiddenException("Only the repository owner can perform this action");
    }

    public boolean isOwner(Repository repository, Long userId) {
        return userId != null && repository.getOwner().getId().equals(userId);
    }
}
