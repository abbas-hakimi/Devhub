package com.versioncontrol.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "repository_tags", indexes = {
        @Index(name = "idx_repository_tags_commit", columnList = "repository_id, commit_id"),
        @Index(name = "idx_repository_tags_created_at", columnList = "repository_id, created_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_repository_tags_repository_name", columnNames = {"repository_id", "name"}),
        @UniqueConstraint(name = "uk_repository_tags_repository_id_id", columnNames = {"repository_id", "id"})
})
public class RepositoryTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(name = "commit_id", nullable = false)
    private Long commitId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commit_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Commit commit;

    @Column(nullable = false, length = 255, columnDefinition = "VARCHAR(255) COLLATE utf8mb4_bin")
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RepositoryTag() { }

    public RepositoryTag(Repository repository, Commit commit, String name, User createdBy) {
        if (repository == null || commit == null || createdBy == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("repository, commit, name, and creator are required");
        }
        if (repository.getId() == null || !repository.getId().equals(commit.getRepository().getId())) {
            throw new IllegalArgumentException("tag commit must belong to its repository");
        }
        this.repository = repository;
        this.commit = commit;
        this.commitId = commit.getId();
        this.name = name;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public Long getCommitId() { return commitId; }
    public Commit getCommit() { return commit; }
    public String getName() { return name; }
    public User getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
