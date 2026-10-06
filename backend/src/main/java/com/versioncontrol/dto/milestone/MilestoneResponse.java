package com.versioncontrol.dto.milestone;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.versioncontrol.entity.Milestone;
import com.versioncontrol.entity.MilestoneState;
import com.versioncontrol.entity.RepositoryVisibility;

public record MilestoneResponse(Long id, RepositorySummary repository, String title, String description, LocalDate dueDate,
        MilestoneState state, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime closedAt) {
    public static MilestoneResponse from(Milestone milestone) {
        var repository = milestone.getRepository();
        return new MilestoneResponse(milestone.getId(), new RepositorySummary(repository.getId(), repository.getName(), repository.getVisibility()),
                milestone.getTitle(), milestone.getDescription(), milestone.getDueDate(), milestone.getState(),
                milestone.getCreatedAt(), milestone.getUpdatedAt(), milestone.getClosedAt());
    }
    public record RepositorySummary(Long id, String name, RepositoryVisibility visibility) { }
}
