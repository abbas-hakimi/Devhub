package com.versioncontrol.dto.project;

import java.util.List;
public record ProjectCardPageResponse(int page, int size, long totalElements, int totalPages, boolean hasNext, List<ProjectCardResponse> items) { }
