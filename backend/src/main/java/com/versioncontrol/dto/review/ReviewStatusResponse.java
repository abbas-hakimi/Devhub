package com.versioncontrol.dto.review;

import java.util.List;

import com.versioncontrol.entity.PullRequestReviewState;

public record ReviewStatusResponse(Long pullRequestId, long totalReviews,
        List<EffectiveReview> latestEffectiveReviews, long currentApprovals,
        long currentChangeRequests, long staleApprovals,
        String reviewedSourceHeadHash, String currentSourceHeadHash,
        boolean approvalThresholdConfigured, Boolean requiredReviewConditionsSatisfied) {
    public record EffectiveReview(Long reviewerId, String reviewerUsername, PullRequestReviewState state,
            String reviewedHeadCommitHash, boolean stale, boolean selfReview) { }
}
