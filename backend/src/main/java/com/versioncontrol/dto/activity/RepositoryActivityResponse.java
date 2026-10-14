package com.versioncontrol.dto.activity;

import com.versioncontrol.entity.RepositoryActivity;
import java.time.LocalDateTime;
public record RepositoryActivityResponse(Long id, Long repositoryId, Long actorId, String actorUsername,
        String eventType, String resourceType, Long resourceId, String summary, LocalDateTime createdAt) {
    public static RepositoryActivityResponse from(RepositoryActivity a) {
        return new RepositoryActivityResponse(a.getId(), a.getRepository().getId(), a.getActor() == null ? null : a.getActor().getId(),
                a.getActor() == null ? null : a.getActor().getUsername(), a.getEventType().name(), a.getResourceType(),
                a.getResourceId(), a.getSummary(), a.getCreatedAt());
    }
}
