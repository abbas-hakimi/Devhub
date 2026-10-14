package com.versioncontrol.dto.discussion;

import java.util.List;
public record DiscussionPageResponse(int page, int size, long totalElements, int totalPages, boolean hasNext,
        List<DiscussionResponse> items) { }
