package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "projects")
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ProjectScope scope;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_id", nullable = false) private User owner;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "repository_id") private Repository repository;
    @Enumerated(EnumType.STRING) @Column(length = 16) private RepositoryVisibility visibility;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ProjectStatus status = ProjectStatus.ACTIVE;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    protected Project() { }
    public Project(String name, String description, ProjectScope scope, User owner, Repository repository, RepositoryVisibility visibility) {
        this.name = name; this.description = description; this.scope = scope; this.owner = owner; this.repository = repository; this.visibility = visibility;
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ProjectScope getScope() { return scope; }
    public User getOwner() { return owner; }
    public Repository getRepository() { return repository; }
    public RepositoryVisibility getVisibility() { return visibility; }
    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
