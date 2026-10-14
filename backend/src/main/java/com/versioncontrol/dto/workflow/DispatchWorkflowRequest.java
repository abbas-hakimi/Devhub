package com.versioncontrol.dto.workflow;

import com.versioncontrol.entity.SimulatedWorkflowOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DispatchWorkflowRequest(@NotBlank @Size(max = 255) String branchName,
        @Pattern(regexp = "[a-fA-F0-9]{64}") String commitSha,
        SimulatedWorkflowOutcome simulatedOutcome) { }
