package com.versioncontrol.dto.workflow;

import java.util.List;

public record WorkflowRunPageResponse(int page, int size, long totalElements, int totalPages, boolean hasNext,
        List<WorkflowRunResponse> items) { }
