package com.versioncontrol.dto.review;

import java.util.List;

public record ReviewCommentPageResponse(int page, int size, long totalElements, int totalPages,
        boolean hasNext, List<ReviewCommentResponse> comments) { }
