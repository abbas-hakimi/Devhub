package com.versioncontrol.dto.workflow;

import com.versioncontrol.entity.WorkflowLogLevel;
import java.time.LocalDateTime;

public record WorkflowJobLogResponse(Long id, Long jobId, int sequenceNumber, WorkflowLogLevel logLevel,
        String message, LocalDateTime createdAt) { }
