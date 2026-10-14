package com.versioncontrol.service;

import com.versioncontrol.entity.WorkflowTriggerType;

public record WorkflowTriggerEvent(Long repositoryId, WorkflowTriggerType triggerType, Long actorId,
        String branchName, String commitSha, String eventKey) { }
