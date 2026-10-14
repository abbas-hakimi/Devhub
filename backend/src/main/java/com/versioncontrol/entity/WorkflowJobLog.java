package com.versioncontrol.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "workflow_job_logs", uniqueConstraints = @UniqueConstraint(name = "uk_workflow_job_logs_sequence", columnNames = {"workflow_job_id", "sequence_number"}),
        indexes = @Index(name = "idx_workflow_job_logs_page", columnList = "workflow_job_id, sequence_number, id"))
public class WorkflowJobLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workflow_job_id", nullable = false) private WorkflowJob job;
    @Column(name = "sequence_number", nullable = false) private int sequenceNumber;
    @Enumerated(EnumType.STRING) @Column(name = "log_level", nullable = false, length = 10) private WorkflowLogLevel logLevel;
    @Column(nullable = false, length = 1000) private String message;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;

    protected WorkflowJobLog() { }
    public WorkflowJobLog(WorkflowJob job, int sequenceNumber, WorkflowLogLevel logLevel, String message) {
        this.job = job; this.sequenceNumber = sequenceNumber; this.logLevel = logLevel; this.message = message;
    }
    public Long getId() { return id; }
    public WorkflowJob getJob() { return job; }
    public int getSequenceNumber() { return sequenceNumber; }
    public WorkflowLogLevel getLogLevel() { return logLevel; }
    public String getMessage() { return message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
