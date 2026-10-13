package com.versioncontrol.dto.pullrequest;

import java.util.List;

public record PullRequestPageResponse(int page, int size, long totalElements, int totalPages,
        boolean hasNext, List<PullRequestResponse> items) { }
