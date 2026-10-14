package com.versioncontrol.dto.release;

import java.util.List;

public record RepositoryReleasePageResponse(int page, int size, long totalElements, int totalPages,
        boolean hasNext, List<RepositoryReleaseResponse> items) { }
