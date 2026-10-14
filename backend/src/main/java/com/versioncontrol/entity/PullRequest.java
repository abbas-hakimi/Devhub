package com.versioncontrol.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "pull_requests",
        indexes = {
                @Index(name = "idx_pull_requests_repository_state", columnList = "repository_id, state, number"),
                @Index(name = "idx_pull_requests_author", columnList = "author_id"),
                @Index(name = "idx_pull_requests_source_branch", columnList = "repository_id, source_branch_id"),
                @Index(name = "idx_pull_requests_target_branch", columnList = "repository_id, target_branch_id"),
                @Index(name = "idx_pull_requests_created_at", columnList = "repository_id, created_at")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_pull_requests_repository_number",
                columnNames = {"repository_id", "number"}))
public class PullRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false)
    private Long number;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false),
            @JoinColumn(name = "source_branch_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private Branch sourceBranch;

    @Column(name = "source_branch_id", nullable = false)
    private Long sourceBranchId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false),
            @JoinColumn(name = "target_branch_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private Branch targetBranch;

    @Column(name = "target_branch_id", nullable = false)
    private Long targetBranchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PullRequestState state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false),
            @JoinColumn(name = "source_head_commit_id_at_creation", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private Commit sourceHeadCommitAtCreation;

    @Column(name = "source_head_commit_id_at_creation", nullable = false)
    private Long sourceHeadCommitIdAtCreation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false),
            @JoinColumn(name = "target_head_commit_id_at_creation", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private Commit targetHeadCommitAtCreation;

    @Column(name = "target_head_commit_id_at_creation", nullable = false)
    private Long targetHeadCommitIdAtCreation;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_by_id")
    private User mergedBy;

    protected PullRequest() { }

    public PullRequest(Repository repository, long number, String title, String description, User author,
            Branch sourceBranch, Branch targetBranch, Commit sourceHeadCommitAtCreation,
            Commit targetHeadCommitAtCreation) {
        this.repository = repository;
        this.number = number;
        this.title = title;
        this.description = description;
        this.author = author;
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.sourceBranchId = sourceBranch.getId();
        this.targetBranchId = targetBranch.getId();
        this.sourceHeadCommitAtCreation = sourceHeadCommitAtCreation;
        this.targetHeadCommitAtCreation = targetHeadCommitAtCreation;
        this.sourceHeadCommitIdAtCreation = sourceHeadCommitAtCreation.getId();
        this.targetHeadCommitIdAtCreation = targetHeadCommitAtCreation.getId();
        this.state = PullRequestState.OPEN;
    }

    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public Long getNumber() { return number; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public User getAuthor() { return author; }
    public Branch getSourceBranch() { return sourceBranch; }
    public Branch getTargetBranch() { return targetBranch; }
    public PullRequestState getState() { return state; }
    public void setState(PullRequestState state) { this.state = state; }
    public Commit getSourceHeadCommitAtCreation() { return sourceHeadCommitAtCreation; }
    public Commit getTargetHeadCommitAtCreation() { return targetHeadCommitAtCreation; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public LocalDateTime getMergedAt() { return mergedAt; }
    public User getMergedBy() { return mergedBy; }
    public Long getSourceBranchId() { return sourceBranchId; }
    public Long getTargetBranchId() { return targetBranchId; }
    public void markMerged(User user, LocalDateTime at) {
        if (user == null || at == null) throw new IllegalArgumentException("merge actor and timestamp are required");
        this.state = PullRequestState.MERGED;
        this.mergedBy = user;
        this.mergedAt = at;
        this.closedAt = null;
    }
}
