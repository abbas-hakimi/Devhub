package com.versioncontrol.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "workflow_runs", uniqueConstraints = @UniqueConstraint(name = "uk_workflow_runs_dispatch", columnNames = {"workflow_id", "dispatch_key"}),
        indexes = {@Index(name = "idx_workflow_runs_repository_created", columnList = "repository_id, created_at, id"),
                @Index(name = "idx_workflow_runs_workflow_created", columnList = "workflow_id, created_at, id"),
                @Index(name = "idx_workflow_runs_status_created", columnList = "status, created_at, id")})
public class WorkflowRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({@JoinColumn(name = "workflow_id", referencedColumnName = "id", nullable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", nullable = false)})
    private WorkflowDefinition workflow;
    @Column(name = "dispatch_key", nullable = false, length = 160) private String dispatchKey;
    @Enumerated(EnumType.STRING) @Column(name = "trigger_type", nullable = false, length = 20) private WorkflowTriggerType triggerType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private WorkflowRunStatus status = WorkflowRunStatus.QUEUED;
    @Column(name = "branch_name", length = 255) private String branchName;
    @Column(name = "commit_sha", length = 64) private String commitSha;
    @Enumerated(EnumType.STRING) @Column(name = "simulated_outcome", nullable = false, length = 16) private SimulatedWorkflowOutcome simulatedOutcome = SimulatedWorkflowOutcome.SUCCESS;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "triggered_by") private User triggeredBy;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;

    protected WorkflowRun() { }
    public WorkflowRun(WorkflowDefinition workflow, String dispatchKey, WorkflowTriggerType triggerType,
            String branchName, String commitSha, SimulatedWorkflowOutcome simulatedOutcome, User triggeredBy) {
        this.workflow = workflow; this.dispatchKey = dispatchKey; this.triggerType = triggerType;
        this.branchName = branchName; this.commitSha = commitSha; this.simulatedOutcome = simulatedOutcome;
        this.triggeredBy = triggeredBy;
    }
    public Long getId() { return id; }
    public WorkflowDefinition getWorkflow() { return workflow; }
    public String getDispatchKey() { return dispatchKey; }
    public WorkflowTriggerType getTriggerType() { return triggerType; }
    public WorkflowRunStatus getStatus() { return status; }
    public void setStatus(WorkflowRunStatus status) { this.status = status; }
    public String getBranchName() { return branchName; }
    public String getCommitSha() { return commitSha; }
    public SimulatedWorkflowOutcome getSimulatedOutcome() { return simulatedOutcome; }
    public User getTriggeredBy() { return triggeredBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
