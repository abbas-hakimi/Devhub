package com.versioncontrol.service;

import com.versioncontrol.dto.workflow.WorkflowJobLogPageResponse;
import com.versioncontrol.dto.workflow.WorkflowJobLogResponse;
import com.versioncontrol.dto.workflow.WorkflowJobResponse;
import com.versioncontrol.entity.*;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.WorkflowJobLogRepository;
import com.versioncontrol.repository.WorkflowJobRepository;
import com.versioncontrol.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowRunExecutionService {
    private final WorkflowRunRepository runs;
    private final WorkflowJobRepository jobs;
    private final WorkflowJobLogRepository logs;
    private final RepositoryAccessService access;
    private final RepositoryActivityService activity;
    private final WorkflowConfigurationValidator configurationValidator;
    private final ObjectMapper mapper;

    public WorkflowRunExecutionService(WorkflowRunRepository runs, WorkflowJobRepository jobs, WorkflowJobLogRepository logs,
            RepositoryAccessService access, RepositoryActivityService activity, WorkflowConfigurationValidator configurationValidator,
            ObjectMapper mapper) {
        this.runs = runs; this.jobs = jobs; this.logs = logs; this.access = access; this.activity = activity;
        this.configurationValidator = configurationValidator;
        this.mapper = mapper;
    }

    @Transactional
    public void cancel(Long runId, Long userId) {
        WorkflowRun initial = runs.findDetailedById(runId).orElseThrow(() -> new ResourceNotFoundException("Workflow run not found"));
        access.requireReadable(initial.getWorkflow().getRepository(), userId);
        access.requireWrite(initial.getWorkflow().getRepository(), userId);
        WorkflowRun run = runs.lockById(runId).orElseThrow(() -> new ResourceNotFoundException("Workflow run not found"));
        access.requireReadable(run.getWorkflow().getRepository(), userId);
        access.requireWrite(run.getWorkflow().getRepository(), userId);
        if (run.getStatus() != WorkflowRunStatus.QUEUED && run.getStatus() != WorkflowRunStatus.RUNNING)
            throw new RepositoryConflictException("Only queued or running workflow runs can be cancelled");
        LocalDateTime now = now();
        run.setStatus(WorkflowRunStatus.CANCELLED); run.setCompletedAt(now);
        for (WorkflowJob job : jobs.findByRun_IdOrderBySequenceNumberAsc(runId)) {
            if (job.getStatus() == WorkflowJobStatus.QUEUED || job.getStatus() == WorkflowJobStatus.RUNNING) {
                job.setStatus(WorkflowJobStatus.CANCELLED); job.setCompletedAt(now);
                addLog(job, WorkflowLogLevel.WARN, "Simulated job cancelled; no repository commands were executed.");
            }
        }
        activity.record(run.getWorkflow().getRepository(), userId, RepositoryActivityEventType.WORKFLOW_RUN_CANCELLED,
                "WORKFLOW_RUN", runId, "Cancelled a workflow run", "WORKFLOW_RUN_CANCELLED:" + runId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowJobResponse> listJobs(Long runId, Long userId) {
        WorkflowRun run = runs.findDetailedById(runId).orElseThrow(() -> new ResourceNotFoundException("Workflow run not found"));
        access.requireReadable(run.getWorkflow().getRepository(), userId);
        return jobs.findByRun_IdOrderBySequenceNumberAsc(runId).stream().map(this::jobResponse).toList();
    }

    @Transactional(readOnly = true)
    public WorkflowJobLogPageResponse listLogs(Long jobId, int page, int size, Long userId) {
        if (page < 0 || page > 2_000 || size < 1 || size > 100)
            throw new com.versioncontrol.exception.InvalidRequestException("Page must be between 0 and 2000 and size between 1 and 100");
        WorkflowJob job = jobs.findDetailedById(jobId).orElseThrow(() -> new ResourceNotFoundException("Workflow job not found"));
        access.requireReadable(job.getRun().getWorkflow().getRepository(), userId);
        Page<WorkflowJobLog> result = logs.findByJob_IdOrderBySequenceNumberAscIdAsc(jobId,
                PageRequest.of(page, size, Sort.unsorted()));
        return new WorkflowJobLogPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), result.getContent().stream().map(this::logResponse).toList());
    }

    @Transactional
    public void advanceOne(Long runId) {
        WorkflowRun run = runs.lockById(runId).orElse(null);
        if (run == null || (run.getStatus() != WorkflowRunStatus.QUEUED && run.getStatus() != WorkflowRunStatus.RUNNING)) return;
        List<WorkflowJob> all = jobs.findByRun_IdOrderBySequenceNumberAsc(runId);
        if (run.getStatus() == WorkflowRunStatus.QUEUED) {
            run.setStatus(WorkflowRunStatus.RUNNING); run.setStartedAt(now());
            return;
        }
        WorkflowJob active = all.stream().filter(j -> j.getStatus() == WorkflowJobStatus.RUNNING).findFirst().orElse(null);
        if (active != null) {
            boolean fail = run.getSimulatedOutcome() == SimulatedWorkflowOutcome.FAILURE
                    && active.getSequenceNumber() == all.size() - 1;
            active.setStatus(fail ? WorkflowJobStatus.FAILURE : WorkflowJobStatus.SUCCESS); active.setCompletedAt(now());
            addLog(active, fail ? WorkflowLogLevel.ERROR : WorkflowLogLevel.INFO,
                    fail ? "Synthetic workflow failure configured for this run." : "Synthetic job completed successfully; no project code ran.");
            if (fail) {
                for (WorkflowJob pending : all) if (pending.getStatus() == WorkflowJobStatus.QUEUED) {
                    pending.setStatus(WorkflowJobStatus.CANCELLED); pending.setCompletedAt(now());
                    addLog(pending, WorkflowLogLevel.WARN, "Skipped because an earlier simulated job failed.");
                }
                finish(run, WorkflowRunStatus.FAILURE, RepositoryActivityEventType.WORKFLOW_RUN_FAILED, "A simulated workflow run failed");
            } else if (all.stream().allMatch(j -> j == active || j.getStatus() == WorkflowJobStatus.SUCCESS)) {
                finish(run, WorkflowRunStatus.SUCCESS, RepositoryActivityEventType.WORKFLOW_RUN_COMPLETED, "A simulated workflow run completed");
            }
            return;
        }
        WorkflowJob queued = all.stream().filter(j -> j.getStatus() == WorkflowJobStatus.QUEUED).findFirst().orElse(null);
        if (queued == null) {
            finish(run, WorkflowRunStatus.SUCCESS, RepositoryActivityEventType.WORKFLOW_RUN_COMPLETED, "A simulated workflow run completed");
            return;
        }
        queued.setStatus(WorkflowJobStatus.RUNNING); queued.setStartedAt(now());
        addLog(queued, WorkflowLogLevel.INFO, "Starting simulated job: " + queued.getName());
        addLog(queued, WorkflowLogLevel.WARN, "Simulation only: no shell, container, or repository code is executed.");
        List<WorkflowConfigurationValidator.JobSpec> configured = configurationValidator
                .validate(parse(run.getWorkflow().getConfigurationJson())).jobs();
        if (queued.getSequenceNumber() < configured.size()) {
            for (String step : configured.get(queued.getSequenceNumber()).steps())
                addLog(queued, WorkflowLogLevel.INFO, "Synthetic " + step.toLowerCase(java.util.Locale.ROOT) + " step started.");
        }
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (Exception invalid) { throw new IllegalStateException("Stored workflow configuration is invalid", invalid); }
    }
    private void finish(WorkflowRun run, WorkflowRunStatus status, RepositoryActivityEventType event, String summary) {
        run.setStatus(status); run.setCompletedAt(now());
        activity.record(run.getWorkflow().getRepository(), null, event, "WORKFLOW_RUN", run.getId(), summary,
                event + ":" + run.getId());
    }
    private void addLog(WorkflowJob job, WorkflowLogLevel level, String message) {
        int sequence = (int) logs.countByJob_Id(job.getId());
        logs.save(new WorkflowJobLog(job, sequence, level, message));
    }
    private WorkflowJobResponse jobResponse(WorkflowJob j) {
        return new WorkflowJobResponse(j.getId(), j.getRun().getId(), j.getName(), j.getStatus(), j.getSequenceNumber(), j.getStartedAt(), j.getCompletedAt());
    }
    private WorkflowJobLogResponse logResponse(WorkflowJobLog l) {
        return new WorkflowJobLogResponse(l.getId(), l.getJob().getId(), l.getSequenceNumber(), l.getLogLevel(), l.getMessage(), l.getCreatedAt());
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
