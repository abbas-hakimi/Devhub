package com.versioncontrol.dto.activity;

import java.util.List;
public record RepositoryActivityPageResponse(int page, int size, long totalElements, int totalPages, boolean hasNext,
        List<RepositoryActivityResponse> items) { }
