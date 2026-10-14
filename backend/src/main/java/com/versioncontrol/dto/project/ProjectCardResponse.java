package com.versioncontrol.dto.project;

import com.versioncontrol.entity.ProjectCard;
import com.versioncontrol.entity.ProjectCardType;
import java.time.LocalDateTime;
public record ProjectCardResponse(Long id, Long projectId, Long columnId, String columnName, String title,
        String description, int position, ProjectCardType cardType, Long issueId, Long pullRequestId,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static ProjectCardResponse from(ProjectCard c) { return new ProjectCardResponse(c.getId(), c.getProject().getId(), c.getColumn().getId(), c.getColumn().getName(), c.getTitle(), c.getDescription(), c.getPosition(), c.getCardType(), c.getIssue() == null ? null : c.getIssue().getId(), c.getPullRequest() == null ? null : c.getPullRequest().getId(), c.getCreatedAt(), c.getUpdatedAt()); }
}
