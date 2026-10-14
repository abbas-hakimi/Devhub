package com.versioncontrol.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.versioncontrol.dto.tag.CreateTagRequest;
import com.versioncontrol.dto.tag.RepositoryTagPageResponse;
import com.versioncontrol.dto.tag.RepositoryTagResponse;
import com.versioncontrol.entity.RepositoryTag;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.RepositoryReleaseRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.RepositoryTagRepository;
import com.versioncontrol.repository.UserRepository;
import com.versioncontrol.validation.TagNameValidator;

@Service
public class RepositoryTagService {
    private static final int MAX_PAGE_SIZE = 100;

    private final RepositoryRepository repositories;
    private final RepositoryTagRepository tags;
    private final RepositoryReleaseRepository releases;
    private final CommitRepository commits;
    private final UserRepository users;
    private final RepositoryAccessService access;

    public RepositoryTagService(RepositoryRepository repositories, RepositoryTagRepository tags,
            RepositoryReleaseRepository releases, CommitRepository commits, UserRepository users,
            RepositoryAccessService access) {
        this.repositories = repositories; this.tags = tags; this.releases = releases;
        this.commits = commits; this.users = users; this.access = access;
    }

    @Transactional
    public RepositoryTagResponse create(Long repositoryId, CreateTagRequest request, Long actorId) {
        var repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        access.requireReadable(repository, actorId);
        access.requireWrite(repository, actorId);
        if (!TagNameValidator.isValidName(request.name())) {
            throw new InvalidRequestException("Tag name is invalid");
        }
        if (tags.existsByRepository_IdAndName(repositoryId, request.name())) throw duplicateTag();
        var commit = commits.findByCommitHash(request.commitHash())
                .filter(found -> found.getRepository().getId().equals(repositoryId))
                .orElseThrow(() -> new ResourceNotFoundException("Commit not found in this repository"));
        var creator = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        try {
            return RepositoryTagResponse.from(tags.saveAndFlush(new RepositoryTag(repository, commit, request.name(), creator)));
        } catch (DataIntegrityViolationException duplicate) {
            throw duplicateTag();
        }
    }

    @Transactional(readOnly = true)
    public RepositoryTagPageResponse list(Long repositoryId, int page, int size, Long actorId) {
        var repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        validatePage(page, size);
        Page<RepositoryTag> result = tags.findByRepository_IdOrderByNameAsc(repositoryId, PageRequest.of(page, size));
        return new RepositoryTagPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), result.getContent().stream().map(RepositoryTagResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public RepositoryTagResponse get(Long repositoryId, String name, Long actorId) {
        var repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        return tags.findDetailedByRepository_IdAndName(repositoryId, name).map(RepositoryTagResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Tag not found"));
    }

    @Transactional
    public void delete(Long repositoryId, String name, Long actorId) {
        var repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        access.requireReadable(repository, actorId);
        access.requireMaintain(repository, actorId);
        RepositoryTag tag = tags.findByRepository_IdAndName(repositoryId, name)
                .orElseThrow(() -> new ResourceNotFoundException("Tag not found"));
        if (releases.existsByRepository_IdAndTag_Id(repositoryId, tag.getId())) {
            throw new RepositoryConflictException("Tag is referenced by a release and cannot be deleted");
        }
        tags.delete(tag);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("Page must be non-negative and size must be between 1 and 100");
        }
    }

    private RepositoryConflictException duplicateTag() {
        return new RepositoryConflictException("A tag with this name already exists in the repository");
    }
}
