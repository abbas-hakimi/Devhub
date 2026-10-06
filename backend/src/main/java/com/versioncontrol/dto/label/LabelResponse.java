package com.versioncontrol.dto.label;

import java.time.LocalDateTime;
import com.versioncontrol.entity.Label;

public record LabelResponse(Long id, RepositorySummary repository, String name, String description, String color,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static LabelResponse from(Label label) {
        var repository = label.getRepository();
        return new LabelResponse(label.getId(), new RepositorySummary(repository.getId(), repository.getName(), repository.getVisibility()),
                label.getName(), label.getDescription(), label.getColor(), label.getCreatedAt(), label.getUpdatedAt());
    }
    public record RepositorySummary(Long id, String name, com.versioncontrol.entity.RepositoryVisibility visibility) { }
}
