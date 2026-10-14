package com.versioncontrol.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.versioncontrol.entity.WorkflowTriggerType;
import java.time.LocalDateTime;

public record WorkflowResponse(Long id, Long repositoryId, String name, String description,
        WorkflowTriggerType triggerType, boolean enabled, int configurationVersion, JsonNode configuration,
        Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) { }
