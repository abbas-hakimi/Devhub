package com.versioncontrol.dto.review;

import java.util.List;

public record ReviewPageResponse(int page, int size, long totalElements, int totalPages,
        boolean hasNext, List<PullRequestReviewResponse> reviews) { }
