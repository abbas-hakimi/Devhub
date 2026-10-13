package com.versioncontrol.dto.review;

import java.util.List;

import com.versioncontrol.entity.PullRequestReviewState;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitPullRequestReviewRequest(
        @NotNull PullRequestReviewState state,
        @Size(max = 20_000) String body,
        @Size(max = 20) List<@Valid InlineReviewCommentRequest> comments) { }
