package com.versioncontrol.dto.review;

import java.time.LocalDateTime;

import com.versioncontrol.entity.PullRequestReviewComment;
import com.versioncontrol.entity.ReviewCommentSide;

public record ReviewCommentResponse(Long id, Long pullRequestId, Long reviewId,
        AuthorSummary author, String path, Integer lineNumber, ReviewCommentSide side,
        String body, String reviewedHeadCommitHash, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public record AuthorSummary(Long id, String username) { }

    public static ReviewCommentResponse from(PullRequestReviewComment comment) {
        return new ReviewCommentResponse(comment.getId(), comment.getPullRequestId(), comment.getReview().getId(),
                new AuthorSummary(comment.getAuthor().getId(), comment.getAuthor().getUsername()),
                comment.getPath(), comment.getLineNumber(), comment.getSide(), comment.getBody(),
                comment.getReview().getReviewedHeadCommitHash(), comment.getCreatedAt(), comment.getUpdatedAt());
    }
}
