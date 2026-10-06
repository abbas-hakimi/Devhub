package com.versioncontrol.dto.comment;

import java.time.LocalDateTime;
import com.versioncontrol.dto.issue.IssueResponse.SafeUser;
import com.versioncontrol.entity.IssueComment;

public record IssueCommentResponse(Long id, Long issueId, SafeUser author, String body, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static IssueCommentResponse from(IssueComment comment) {
        return new IssueCommentResponse(comment.getId(), comment.getIssue().getId(), SafeUser.from(comment.getAuthor()),
                comment.getBody(), comment.getCreatedAt(), comment.getUpdatedAt());
    }
}
