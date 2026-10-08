package com.versioncontrol.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.versioncontrol.validation.BranchNameValidator;
import com.versioncontrol.validation.ValidBranchName;

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
@Table(name = "branches",
        indexes = @Index(name = "idx_branches_head_commit", columnList = "head_commit_id"),
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_branches_repository_name", columnNames = {"repository_id", "name"}),
                @UniqueConstraint(name = "uk_branches_repository_id_id", columnNames = {"repository_id", "id"})
        })
public class Branch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @ValidBranchName
    @Column(nullable = false, length = 255,
            columnDefinition = "VARCHAR(255) COLLATE utf8mb4_bin")
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "head_commit_id", nullable = false)
    private Commit headCommit;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Branch() { }

    public Branch(Repository repository, String name, Commit headCommit) {
        if (repository == null) throw new IllegalArgumentException("repository is required");
        if (!BranchNameValidator.isValidName(name)) throw new IllegalArgumentException("branch name is invalid");
        if (headCommit == null) throw new IllegalArgumentException("head commit is required");
        if (!sameRepository(repository, headCommit.getRepository())) {
            throw new IllegalArgumentException("head commit must belong to the branch repository");
        }
        this.repository = repository;
        this.name = name;
        this.headCommit = headCommit;
    }

    private boolean sameRepository(Repository first, Repository second) {
        return first == second || (first.getId() != null && first.getId().equals(second.getId()));
    }

    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public String getName() { return name; }
    public Commit getHeadCommit() { return headCommit; }
    public void setHeadCommit(Commit headCommit) {
        if (headCommit == null || !sameRepository(repository, headCommit.getRepository())) {
            throw new IllegalArgumentException("head commit must belong to the branch repository");
        }
        this.headCommit = headCommit;
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
