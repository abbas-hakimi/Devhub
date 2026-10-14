package com.versioncontrol.dto.workflow;

import java.util.List;

public record WorkflowPageResponse(int page, int size, long totalElements, int totalPages, boolean hasNext,
        List<WorkflowResponse> items) { }
