package com.versioncontrol.dto.review;

import java.time.LocalDateTime;
import java.util.List;

import com.versioncontrol.entity.PullRequestReview;
import com.versioncontrol.entity.PullRequestReviewState;

public record PullRequestReviewResponse(Long id, Long pullRequestId, ReviewerSummary reviewer,
        PullRequestReviewState state, String body, Long reviewedHeadCommitId,
        String reviewedHeadCommitHash, LocalDateTime createdAt, LocalDateTime updatedAt,
        List<ReviewCommentResponse> comments) {
    public record ReviewerSummary(Long id, String username) { }

    public static PullRequestReviewResponse from(PullRequestReview review, List<ReviewCommentResponse> comments) {
        return new PullRequestReviewResponse(review.getId(), review.getPullRequest().getId(),
                new ReviewerSummary(review.getReviewer().getId(), review.getReviewer().getUsername()),
                review.getState(), review.getBody(), review.getReviewedHeadCommitId(),
                review.getReviewedHeadCommitHash(), review.getCreatedAt(), review.getUpdatedAt(), List.copyOf(comments));
    }
}
