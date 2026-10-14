package com.versioncontrol.dto.workflow;

import com.versioncontrol.entity.WorkflowRunStatus;
import com.versioncontrol.entity.WorkflowTriggerType;
import java.time.LocalDateTime;

public record WorkflowRunResponse(Long id, Long workflowId, String workflowName, Long repositoryId,
        WorkflowTriggerType triggerType, WorkflowRunStatus status, String branchName, String commitSha,
        Long triggeredBy, LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime completedAt,
        boolean simulated) { }
