package com.versioncontrol.service;

import com.versioncontrol.entity.WorkflowTriggerType;
import com.versioncontrol.repository.WorkflowDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WorkflowTriggerListener {
    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerListener.class);
    private final WorkflowDefinitionRepository workflows;
    private final WorkflowManagementService management;
    public WorkflowTriggerListener(WorkflowDefinitionRepository workflows, WorkflowManagementService management) {
        this.workflows = workflows; this.management = management;
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTrigger(WorkflowTriggerEvent event) {
        try {
            for (var workflow : workflows.findByRepository_IdAndEnabledTrueAndTriggerType(event.repositoryId(), event.triggerType())) {
                try { management.dispatchTriggered(workflow, event); }
                catch (RuntimeException failure) {
                    // Trigger processing is best-effort after the originating commit/PR transaction is durable.
                    log.error("Could not dispatch {} workflow {} for repository {}", event.triggerType(), workflow.getId(), event.repositoryId(), failure);
                }
            }
        } catch (RuntimeException failure) {
            log.error("Could not enumerate {} workflows for repository {}", event.triggerType(), event.repositoryId(), failure);
        }
    }
}
