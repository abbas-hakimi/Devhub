package com.versioncontrol.entity;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Entity
@Table(name = "commits",
        indexes = {
                @Index(name = "idx_commits_repository_created_id", columnList = "repository_id, created_at, id"),
                @Index(name = "idx_commits_repository_parent", columnList = "repository_id, parent_commit_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_commits_commit_hash", columnNames = "commit_hash"),
                @UniqueConstraint(name = "uk_commits_repository_id_id", columnNames = {"repository_id", "id"})
        })
public class Commit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    @Column(name = "commit_hash", nullable = false, length = 64,
            columnDefinition = "CHAR(64) COLLATE ascii_bin")
    private String commitHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "root_tree_id", nullable = false)
    private Tree rootTree;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_commit_id")
    private Commit parentCommit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Commit() { }

    public Commit(Repository repository, String commitHash, String message, User author, Tree rootTree, Commit parentCommit) {
        this(repository, commitHash, message, author, rootTree, parentCommit,
                LocalDateTime.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS));
    }

    public Commit(Repository repository, String commitHash, String message, User author, Tree rootTree,
            Commit parentCommit, LocalDateTime createdAt) {
        if (repository == null) throw new IllegalArgumentException("repository is required");
        if (commitHash == null || !commitHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("commit hash must be 64 lowercase hexadecimal characters");
        }
        if (message == null) throw new IllegalArgumentException("commit message is required");
        if (author == null) throw new IllegalArgumentException("commit author is required");
        if (rootTree == null) throw new IllegalArgumentException("root tree is required");
        if (createdAt == null) throw new IllegalArgumentException("commit creation time is required");
        if (parentCommit != null && !sameRepository(repository, parentCommit.getRepository())) {
            throw new IllegalArgumentException("parent commit must belong to the same repository");
        }
        this.repository = repository;
        this.commitHash = commitHash;
        this.message = message;
        this.author = author;
        this.rootTree = rootTree;
        this.parentCommit = parentCommit;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    private boolean sameRepository(Repository first, Repository second) {
        return first == second || (first.getId() != null && first.getId().equals(second.getId()));
    }

    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public String getCommitHash() { return commitHash; }
    public String getMessage() { return message; }
    public User getAuthor() { return author; }
    public Tree getRootTree() { return rootTree; }
    public Commit getParentCommit() { return parentCommit; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
