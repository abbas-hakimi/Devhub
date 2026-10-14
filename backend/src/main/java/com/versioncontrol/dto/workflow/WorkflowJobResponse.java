package com.versioncontrol.dto.workflow;

import com.versioncontrol.entity.WorkflowJobStatus;
import java.time.LocalDateTime;

public record WorkflowJobResponse(Long id, Long runId, String name, WorkflowJobStatus status, int sequenceNumber,
        LocalDateTime startedAt, LocalDateTime completedAt) { }
