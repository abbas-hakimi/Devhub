package com.versioncontrol.dto.release;

import java.time.LocalDateTime;

import com.versioncontrol.entity.RepositoryRelease;

public record RepositoryReleaseResponse(Long id, Long repositoryId, Long tagId, String tagName,
        String commitHash, String title, String description, boolean draft, boolean prerelease,
        Long createdById, String createdByUsername, LocalDateTime createdAt,
        LocalDateTime updatedAt, LocalDateTime publishedAt) {
    public static RepositoryReleaseResponse from(RepositoryRelease release) {
        return new RepositoryReleaseResponse(release.getId(), release.getRepository().getId(), release.getTagId(),
                release.getTag().getName(), release.getTag().getCommit().getCommitHash(), release.getTitle(),
                release.getDescription(), release.isDraft(), release.isPrerelease(), release.getCreatedBy().getId(),
                release.getCreatedBy().getUsername(), release.getCreatedAt(), release.getUpdatedAt(), release.getPublishedAt());
    }
}
