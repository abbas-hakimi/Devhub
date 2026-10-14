package com.versioncontrol.dto.tag;

import java.util.List;

public record RepositoryTagPageResponse(int page, int size, long totalElements, int totalPages,
        boolean hasNext, List<RepositoryTagResponse> items) { }
