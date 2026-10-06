package com.versioncontrol.dto.issue;

import java.time.LocalDateTime;
import java.util.List;
import com.versioncontrol.dto.label.LabelResponse;
import com.versioncontrol.entity.Issue;
import com.versioncontrol.entity.IssueStatus;

public record IssueResponse(Long id, RepositorySummary repository, SafeUser author, SafeUser assignee,
        String title, String description, IssueStatus status, LocalDateTime createdAt,
        LocalDateTime updatedAt, LocalDateTime closedAt, List<LabelResponse> labels) {
    public static IssueResponse from(Issue issue, List<LabelResponse> labels) {
        var repository = issue.getRepository();
        return new IssueResponse(issue.getId(), new RepositorySummary(repository.getId(), repository.getName(), repository.getVisibility()),
                SafeUser.from(issue.getAuthor()), SafeUser.from(issue.getAssignee()), issue.getTitle(), issue.getDescription(),
                issue.getStatus(), issue.getCreatedAt(), issue.getUpdatedAt(), issue.getClosedAt(), List.copyOf(labels));
    }
    public record RepositorySummary(Long id, String name, com.versioncontrol.entity.RepositoryVisibility visibility) { }
    public record SafeUser(Long id, String username, String displayName) {
        public static SafeUser from(com.versioncontrol.entity.User user) {
            return user == null ? null : new SafeUser(user.getId(), user.getUsername(), user.getDisplayName());
        }
    }
}
