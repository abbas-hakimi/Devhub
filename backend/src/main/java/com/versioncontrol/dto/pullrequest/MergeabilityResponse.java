package com.versioncontrol.dto.pullrequest;

import java.util.List;

public record MergeabilityResponse(Long pullRequestId, boolean mergeable, String sourceHead,
        String targetHead, String mergeBase, long aheadBy, long behindBy,
        List<String> conflictPaths, boolean fastForwardEligible, long currentApprovals,
        long currentChangeRequests, String reason) { }
