package com.versioncontrol.dto.pullrequest;

import java.time.LocalDateTime;
import java.util.List;

import com.versioncontrol.entity.PullRequestState;

public record MergePullRequestResponse(Long pullRequestId, PullRequestState state,
        MergeStrategy strategy, String sourceHead, String targetHead, String mergeBase,
        String mergeCommitHash, LocalDateTime mergedAt, List<String> conflictPaths, String message) { }
