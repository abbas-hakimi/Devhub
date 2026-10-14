package com.versioncontrol.service;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.versioncontrol.dto.repository.CreateRepositoryRequest;
import com.versioncontrol.dto.repository.RepositoryResponse;
import com.versioncontrol.dto.repository.UpdateRepositoryRequest;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryVisibility;
import com.versioncontrol.entity.User;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.RepositoryStarRepository;
import com.versioncontrol.repository.UserRepository;

@Service
public class RepositoryService {
    private final RepositoryRepository repositories;
    private final UserRepository users;
    private final RepositoryStarRepository stars;
    private final RepositoryAccessService access;
    private final RepositoryActivityService activity;

    public RepositoryService(RepositoryRepository repositories, UserRepository users, RepositoryStarRepository stars, RepositoryAccessService access,
            RepositoryActivityService activity) {
        this.repositories = repositories;
        this.users = users;
        this.stars = stars;
        this.access = access;
        this.activity = activity;
    }

    @Transactional
    public RepositoryResponse create(CreateRepositoryRequest request, Long authenticatedUserId) {
        User owner = users.findById(authenticatedUserId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String name = request.name().trim();
        if (repositories.existsByOwner_IdAndNameIgnoreCase(owner.getId(), name)) {
            throw new RepositoryConflictException("A repository with this name already exists for this owner");
        }
        Repository repository = new Repository(name, cleanDescription(request.description()), request.visibility(), owner);
        try {
            Repository saved = repositories.saveAndFlush(repository);
            activity.record(saved, owner.getId(), com.versioncontrol.entity.RepositoryActivityEventType.REPOSITORY_CREATED,
                    "REPOSITORY", saved.getId(), "Created repository " + saved.getName(), "REPOSITORY_CREATED:" + saved.getId());
            return RepositoryResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new RepositoryConflictException("A repository with this name already exists for this owner");
        }
    }

    @Transactional(readOnly = true)
    public RepositoryResponse get(Long id, Long authenticatedUserId) {
        Repository repository = findRepository(id);
        access.requireReadable(repository, authenticatedUserId);
        return RepositoryResponse.from(repository);
    }

    @Transactional(readOnly = true)
    public List<RepositoryResponse> listPublic(String owner, String name) {
        boolean hasOwner = owner != null && !owner.isBlank();
        boolean hasName = name != null && !name.isBlank();
        List<Repository> result;
        if (hasOwner && hasName) {
            result = repositories.findByVisibilityAndOwner_UsernameIgnoreCaseAndNameContainingIgnoreCaseOrderByCreatedAtDesc(
                    RepositoryVisibility.PUBLIC, owner.trim(), name.trim());
        } else if (hasOwner) {
            result = repositories.findByVisibilityAndOwner_UsernameIgnoreCaseOrderByCreatedAtDesc(RepositoryVisibility.PUBLIC, owner.trim());
        } else if (hasName) {
            result = repositories.findByVisibilityAndNameContainingIgnoreCaseOrderByCreatedAtDesc(RepositoryVisibility.PUBLIC, name.trim());
        } else {
            result = repositories.findByVisibilityOrderByCreatedAtDesc(RepositoryVisibility.PUBLIC);
        }
        return result.stream().map(RepositoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RepositoryResponse> listMine(Long authenticatedUserId) {
        java.util.Set<Long> ids = new java.util.HashSet<>(access.acceptedRepositoryIds(authenticatedUserId));
        repositories.findByOwner_IdOrderByCreatedAtDesc(authenticatedUserId).forEach(r -> ids.add(r.getId()));
        return repositories.findByIdInOrderByCreatedAtDesc(List.copyOf(ids)).stream().map(RepositoryResponse::from).toList();
    }

    @Transactional
    public RepositoryResponse update(Long id, UpdateRepositoryRequest request, Long authenticatedUserId) {
        Repository repository = findRepository(id);
        access.requireMaintain(repository, authenticatedUserId);
        String name = request.name().trim();
        if (repositories.existsByOwner_IdAndNameIgnoreCaseAndIdNot(repository.getOwner().getId(), name, id)) {
            throw new RepositoryConflictException("A repository with this name already exists for this owner");
        }
        repository.setName(name);
        repository.setDescription(cleanDescription(request.description()));
        repository.setVisibility(request.visibility());
        try {
            return RepositoryResponse.from(repositories.saveAndFlush(repository));
        } catch (DataIntegrityViolationException exception) {
            throw new RepositoryConflictException("A repository with this name already exists for this owner");
        }
    }

    @Transactional
    public void delete(Long id, Long authenticatedUserId) {
        Repository repository = findRepository(id);
        access.requireOwner(repository, authenticatedUserId);
        if (stars.existsByRepository_Id(id)) {
            throw new RepositoryConflictException("Repository has dependent star records and cannot be deleted yet");
        }
        repositories.delete(repository);
        try {
            repositories.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new RepositoryConflictException("Repository has dependent records and cannot be deleted yet");
        }
    }

    private Repository findRepository(Long id) {
        return repositories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
    }

    private String cleanDescription(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }
}
