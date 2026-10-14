package com.versioncontrol.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;

@Entity
@Table(name = "workflow_jobs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_workflow_jobs_sequence", columnNames = {"workflow_run_id", "sequence_number"}),
        @UniqueConstraint(name = "uk_workflow_jobs_name", columnNames = {"workflow_run_id", "name"})
}, indexes = @Index(name = "idx_workflow_jobs_run_status", columnList = "workflow_run_id, status, sequence_number"))
public class WorkflowJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workflow_run_id", nullable = false) private WorkflowRun run;
    @Column(nullable = false, length = 100) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private WorkflowJobStatus status = WorkflowJobStatus.QUEUED;
    @Column(name = "sequence_number", nullable = false) private int sequenceNumber;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;

    protected WorkflowJob() { }
    public WorkflowJob(WorkflowRun run, String name, int sequenceNumber) { this.run = run; this.name = name; this.sequenceNumber = sequenceNumber; }
    public Long getId() { return id; }
    public WorkflowRun getRun() { return run; }
    public String getName() { return name; }
    public WorkflowJobStatus getStatus() { return status; }
    public void setStatus(WorkflowJobStatus status) { this.status = status; }
    public int getSequenceNumber() { return sequenceNumber; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
