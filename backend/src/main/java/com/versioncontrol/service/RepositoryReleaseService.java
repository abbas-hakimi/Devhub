package com.versioncontrol.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.versioncontrol.dto.release.CreateReleaseRequest;
import com.versioncontrol.dto.release.RepositoryReleasePageResponse;
import com.versioncontrol.dto.release.RepositoryReleaseResponse;
import com.versioncontrol.dto.release.UpdateReleaseRequest;
import com.versioncontrol.entity.RepositoryRelease;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.RepositoryReleaseRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.RepositoryTagRepository;
import com.versioncontrol.repository.UserRepository;

@Service
public class RepositoryReleaseService {
    private static final int MAX_PAGE_SIZE = 100;

    private final RepositoryRepository repositories;
    private final RepositoryTagRepository tags;
    private final RepositoryReleaseRepository releases;
    private final UserRepository users;
    private final RepositoryAccessService access;

    public RepositoryReleaseService(RepositoryRepository repositories, RepositoryTagRepository tags,
            RepositoryReleaseRepository releases, UserRepository users, RepositoryAccessService access) {
        this.repositories = repositories; this.tags = tags; this.releases = releases;
        this.users = users; this.access = access;
    }

    @Transactional
    public RepositoryReleaseResponse create(Long repositoryId, CreateReleaseRequest request, Long actorId) {
        var repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        access.requireReadable(repository, actorId);
        access.requireMaintain(repository, actorId);
        if (request.title().isBlank()) throw new InvalidRequestException("Release title must not be blank");
        var tag = tags.findByRepository_IdAndName(repositoryId, request.tagName())
                .orElseThrow(() -> new ResourceNotFoundException("Tag not found"));
        if (releases.existsByRepository_IdAndTag_Id(repositoryId, tag.getId())) throw duplicateRelease();
        boolean draft = request.draft() == null || request.draft();
        boolean prerelease = Boolean.TRUE.equals(request.prerelease());
        LocalDateTime publishedAt = draft ? null : now();
        var creator = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        try {
            return RepositoryReleaseResponse.from(releases.saveAndFlush(new RepositoryRelease(repository, tag,
                    request.title().trim(), clean(request.description()), draft, prerelease, creator, publishedAt)));
        } catch (DataIntegrityViolationException duplicate) {
            throw duplicateRelease();
        }
    }

    @Transactional(readOnly = true)
    public RepositoryReleasePageResponse list(Long repositoryId, int page, int size, Long actorId) {
        var repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        validatePage(page, size);
        Page<RepositoryRelease> result = access.hasMaintain(repository, actorId)
                ? releases.findByRepository_IdOrderByCreatedAtDescIdDesc(repositoryId, PageRequest.of(page, size))
                : releases.findByRepository_IdAndDraftFalseOrderByPublishedAtDescIdDesc(repositoryId, PageRequest.of(page, size));
        return new RepositoryReleasePageResponse(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), result.getContent().stream().map(RepositoryReleaseResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public RepositoryReleaseResponse get(Long repositoryId, Long releaseId, Long actorId) {
        var repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        RepositoryRelease release = findRelease(repositoryId, releaseId);
        if (release.isDraft() && !access.hasMaintain(repository, actorId)) {
            throw new ResourceNotFoundException("Release not found");
        }
        return RepositoryReleaseResponse.from(release);
    }

    @Transactional
    public RepositoryReleaseResponse update(Long repositoryId, Long releaseId, UpdateReleaseRequest request, Long actorId) {
        var repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        access.requireReadable(repository, actorId);
        access.requireMaintain(repository, actorId);
        RepositoryRelease release = findRelease(repositoryId, releaseId);
        if (!request.isTitleSpecified() && !request.isDescriptionSpecified()
                && !request.isDraftSpecified() && !request.isPrereleaseSpecified()) {
            throw new InvalidRequestException("At least one release field must be supplied");
        }
        if (request.isTitleSpecified()) {
            if (request.getTitle() == null || request.getTitle().isBlank()) {
                throw new InvalidRequestException("Release title must not be blank");
            }
            release.setTitle(request.getTitle().trim());
        }
        if (request.isDescriptionSpecified()) release.setDescription(clean(request.getDescription()));
        if (request.isPrereleaseSpecified()) {
            if (request.getPrerelease() == null) throw new InvalidRequestException("prerelease must not be null");
            release.setPrerelease(request.getPrerelease());
        }
        if (request.isDraftSpecified()) {
            if (request.getDraft() == null) throw new InvalidRequestException("draft must not be null");
            if (release.isDraft() != request.getDraft()) {
                release.setDraft(request.getDraft());
                release.setPublishedAt(request.getDraft() ? null : now());
            }
        }
        try {
            return RepositoryReleaseResponse.from(releases.saveAndFlush(release));
        } catch (DataIntegrityViolationException conflict) {
            throw new RepositoryConflictException("Release update conflicts with existing data");
        }
    }

    @Transactional
    public void delete(Long repositoryId, Long releaseId, Long actorId) {
        var repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        access.requireReadable(repository, actorId);
        access.requireMaintain(repository, actorId);
        releases.delete(findRelease(repositoryId, releaseId));
    }

    private RepositoryRelease findRelease(Long repositoryId, Long releaseId) {
        return releases.findDetailedByRepository_IdAndId(repositoryId, releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Release not found"));
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("Page must be non-negative and size must be between 1 and 100");
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private RepositoryConflictException duplicateRelease() {
        return new RepositoryConflictException("A release already exists for this tag");
    }
}
