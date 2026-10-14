package com.versioncontrol.controller;

import com.versioncontrol.dto.workflow.*;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.WorkflowManagementService;
import com.versioncontrol.service.WorkflowRunExecutionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkflowController {
    private final WorkflowManagementService workflows;
    private final WorkflowRunExecutionService execution;
    public WorkflowController(WorkflowManagementService workflows, WorkflowRunExecutionService execution) {
        this.workflows = workflows; this.execution = execution;
    }

    @PostMapping("/api/repositories/{repositoryId}/workflows") @ResponseStatus(HttpStatus.CREATED)
    public WorkflowResponse create(@PathVariable Long repositoryId, @Valid @RequestBody CreateWorkflowRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return workflows.create(repositoryId, request, principal.getId()); }
    @GetMapping("/api/repositories/{repositoryId}/workflows")
    public WorkflowPageResponse list(@PathVariable Long repositoryId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal UserPrincipal principal) {
        return workflows.list(repositoryId, page, size, id(principal));
    }
    @GetMapping("/api/workflows/{workflowId}")
    public WorkflowResponse get(@PathVariable Long workflowId, @AuthenticationPrincipal UserPrincipal principal) { return workflows.get(workflowId, id(principal)); }
    @PatchMapping("/api/workflows/{workflowId}")
    public WorkflowResponse update(@PathVariable Long workflowId, @RequestBody UpdateWorkflowRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return workflows.update(workflowId, request, principal.getId()); }
    @DeleteMapping("/api/workflows/{workflowId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long workflowId, @AuthenticationPrincipal UserPrincipal principal) { workflows.delete(workflowId, principal.getId()); }
    @PostMapping("/api/workflows/{workflowId}/enable")
    public WorkflowResponse enable(@PathVariable Long workflowId, @AuthenticationPrincipal UserPrincipal principal) {
        return workflows.setEnabled(workflowId, principal.getId(), true);
    }
    @PostMapping("/api/workflows/{workflowId}/disable")
    public WorkflowResponse disable(@PathVariable Long workflowId, @AuthenticationPrincipal UserPrincipal principal) {
        return workflows.setEnabled(workflowId, principal.getId(), false);
    }
    @PostMapping("/api/workflows/{workflowId}/dispatch") @ResponseStatus(HttpStatus.ACCEPTED)
    public WorkflowRunResponse dispatch(@PathVariable Long workflowId, @Valid @RequestBody DispatchWorkflowRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return workflows.dispatch(workflowId, request, principal.getId()); }
    @GetMapping("/api/workflows/{workflowId}/runs")
    public WorkflowRunPageResponse workflowRuns(@PathVariable Long workflowId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal UserPrincipal principal) {
        return workflows.listRunsForWorkflow(workflowId, page, size, id(principal));
    }
    @GetMapping("/api/repositories/{repositoryId}/workflow-runs")
    public WorkflowRunPageResponse repositoryRuns(@PathVariable Long repositoryId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal UserPrincipal principal) {
        return workflows.listRunsForRepository(repositoryId, page, size, id(principal));
    }
    @GetMapping("/api/workflow-runs/{runId}")
    public WorkflowRunResponse getRun(@PathVariable Long runId, @AuthenticationPrincipal UserPrincipal principal) {
        return workflows.getRun(runId, id(principal));
    }
    @PostMapping("/api/workflow-runs/{runId}/cancel")
    public WorkflowRunResponse cancel(@PathVariable Long runId, @AuthenticationPrincipal UserPrincipal principal) {
        execution.cancel(runId, principal.getId()); return workflows.getRun(runId, principal.getId());
    }
    @GetMapping("/api/workflow-runs/{runId}/jobs")
    public List<WorkflowJobResponse> jobs(@PathVariable Long runId, @AuthenticationPrincipal UserPrincipal principal) {
        return execution.listJobs(runId, id(principal));
    }
    @GetMapping("/api/workflow-jobs/{jobId}/logs")
    public WorkflowJobLogPageResponse logs(@PathVariable Long jobId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, @AuthenticationPrincipal UserPrincipal principal) {
        return execution.listLogs(jobId, page, size, id(principal));
    }
    private Long id(UserPrincipal principal) { return principal == null ? null : principal.getId(); }
}
