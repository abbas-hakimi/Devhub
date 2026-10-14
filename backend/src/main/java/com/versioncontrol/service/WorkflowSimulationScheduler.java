package com.versioncontrol.service;

import com.versioncontrol.entity.WorkflowRunStatus;
import com.versioncontrol.repository.WorkflowRunRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkflowSimulationScheduler {
    private static final Logger log = LoggerFactory.getLogger(WorkflowSimulationScheduler.class);
    private final WorkflowRunRepository runs;
    private final WorkflowRunExecutionService execution;
    public WorkflowSimulationScheduler(WorkflowRunRepository runs, WorkflowRunExecutionService execution) {
        this.runs = runs; this.execution = execution;
    }
    @Scheduled(fixedDelayString = "${workflow.simulation.fixed-delay-ms:250}",
            initialDelayString = "${workflow.simulation.initial-delay-ms:250}")
    public void tick() {
        List<Long> ids = runs.findProcessableIds(List.of(WorkflowRunStatus.QUEUED, WorkflowRunStatus.RUNNING), PageRequest.of(0, 25));
        for (Long id : ids) {
            try { execution.advanceOne(id); }
            catch (RuntimeException failure) { log.error("Unable to advance simulated workflow run {}", id, failure); }
        }
    }
}
