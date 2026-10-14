package com.versioncontrol.dto.workflow;

import java.util.List;

public record WorkflowJobLogPageResponse(int page, int size, long totalElements, int totalPages, boolean hasNext,
        List<WorkflowJobLogResponse> items) { }
