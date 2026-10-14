package com.versioncontrol.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "workflow_definitions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_workflow_id_repository", columnNames = {"id", "repository_id"}),
        @UniqueConstraint(name = "uk_workflow_repository_name", columnNames = {"repository_id", "name"})
})
public class WorkflowDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private Repository repository;
    @Column(nullable = false, length = 100) private String name;
    @Column(length = 500) private String description;
    @Enumerated(EnumType.STRING) @Column(name = "trigger_type", nullable = false, length = 20) private WorkflowTriggerType triggerType;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "configuration_version", nullable = false) private int configurationVersion = 1;
    @Column(name = "configuration_json", nullable = false, columnDefinition = "TEXT") private String configurationJson;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by") private User createdBy;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected WorkflowDefinition() { }
    public WorkflowDefinition(Repository repository, String name, String description, WorkflowTriggerType triggerType,
            int configurationVersion, String configurationJson, User createdBy) {
        this.repository = repository; this.name = name; this.description = description; this.triggerType = triggerType;
        this.configurationVersion = configurationVersion; this.configurationJson = configurationJson; this.createdBy = createdBy;
    }
    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public WorkflowTriggerType getTriggerType() { return triggerType; }
    public void setTriggerType(WorkflowTriggerType triggerType) { this.triggerType = triggerType; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getConfigurationVersion() { return configurationVersion; }
    public void setConfigurationVersion(int configurationVersion) { this.configurationVersion = configurationVersion; }
    public String getConfigurationJson() { return configurationJson; }
    public void setConfigurationJson(String configurationJson) { this.configurationJson = configurationJson; }
    public User getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
