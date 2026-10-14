package com.versioncontrol.dto.tag;

import java.time.LocalDateTime;

import com.versioncontrol.entity.RepositoryTag;

public record RepositoryTagResponse(Long id, Long repositoryId, String name, String commitHash,
        String commitMessage, Long createdById, String createdByUsername, LocalDateTime createdAt) {
    public static RepositoryTagResponse from(RepositoryTag tag) {
        return new RepositoryTagResponse(tag.getId(), tag.getRepository().getId(), tag.getName(),
                tag.getCommit().getCommitHash(), tag.getCommit().getMessage(), tag.getCreatedBy().getId(),
                tag.getCreatedBy().getUsername(), tag.getCreatedAt());
    }
}
