package com.versioncontrol.dto.project;

import java.util.List;
public record ProjectPageResponse(int page, int size, long totalElements, int totalPages, boolean hasNext, List<ProjectResponse> items) { }
