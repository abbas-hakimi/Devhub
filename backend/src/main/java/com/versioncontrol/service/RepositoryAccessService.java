package com.versioncontrol.service;

import org.springframework.stereotype.Service;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryVisibility;
import com.versioncontrol.entity.CollaboratorRole;
import com.versioncontrol.entity.InvitationStatus;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.RepositoryCollaboratorRepository;
import com.versioncontrol.repository.RepositoryRepository;

@Service
public class RepositoryAccessService {
    private final RepositoryRepository repositories;
    private final RepositoryCollaboratorRepository collaborators;
    public RepositoryAccessService(RepositoryRepository repositories, RepositoryCollaboratorRepository collaborators) {
        this.repositories = repositories; this.collaborators = collaborators;
    }

    public Repository find(Long id) {
        return repositories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
    }

    public void requireReadable(Repository repository, Long userId) {
        if (repository.getVisibility() == RepositoryVisibility.PRIVATE
                && !isOwner(repository, userId) && roleOf(repository, userId) == null) {
            throw new ResourceNotFoundException("Resource not found");
        }
    }

    public void requireOwner(Repository repository, Long userId) {
        if (!isOwner(repository, userId)) throw new ResourceForbiddenException("Only the repository owner can perform this action");
    }

    public void requireWrite(Repository repository, Long userId) {
        if (isOwner(repository, userId)) return;
        CollaboratorRole role = roleOf(repository, userId);
        if (role != CollaboratorRole.WRITE && role != CollaboratorRole.MAINTAIN)
            throw new ResourceForbiddenException("Write access to this repository is required");
    }

    public void requireMaintain(Repository repository, Long userId) {
        if (isOwner(repository, userId)) return;
        CollaboratorRole role = roleOf(repository, userId);
        if (role != CollaboratorRole.MAINTAIN)
            throw new ResourceForbiddenException("Maintainer access to this repository is required");
    }

    public CollaboratorRole roleOf(Repository repository, Long userId) {
        if (isOwner(repository, userId)) return null;
        if (userId == null) return null;
        return collaborators.findByRepository_IdAndUser_Id(repository.getId(), userId)
                .filter(c -> c.getStatus() == InvitationStatus.ACCEPTED).map(c -> c.getRole()).orElse(null);
    }

    public boolean hasWrite(Repository repository, Long userId) {
        if (isOwner(repository, userId)) return true;
        CollaboratorRole role = roleOf(repository, userId);
        return role == CollaboratorRole.WRITE || role == CollaboratorRole.MAINTAIN;
    }

    public boolean hasMaintain(Repository repository, Long userId) {
        return isOwner(repository, userId) || roleOf(repository, userId) == CollaboratorRole.MAINTAIN;
    }

    public java.util.List<Long> acceptedRepositoryIds(Long userId) {
        return userId == null ? java.util.List.of() : collaborators.acceptedRepositoryIds(userId);
    }

    public boolean isOwner(Repository repository, Long userId) {
        return userId != null && repository.getOwner().getId().equals(userId);
    }
}
