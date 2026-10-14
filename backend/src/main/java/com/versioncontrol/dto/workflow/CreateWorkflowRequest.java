package com.versioncontrol.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.versioncontrol.entity.WorkflowTriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWorkflowRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
        @NotNull WorkflowTriggerType triggerType, @NotNull JsonNode configuration) { }
