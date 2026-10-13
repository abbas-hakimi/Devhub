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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "pull_request_reviews", indexes = {
        @Index(name = "idx_pull_request_reviews_pr_created", columnList = "pull_request_id, created_at, id"),
        @Index(name = "idx_pull_request_reviews_reviewer", columnList = "reviewer_id, pull_request_id, created_at"),
        @Index(name = "idx_pull_request_reviews_state", columnList = "pull_request_id, state, created_at"),
        @Index(name = "idx_pull_request_reviews_head", columnList = "reviewed_head_commit_id")
})
public class PullRequestReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private PullRequestReviewState state;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "reviewed_head_commit_id", nullable = false)
    private Long reviewedHeadCommitId;

    @Column(name = "reviewed_head_commit_hash", nullable = false, length = 64)
    private String reviewedHeadCommitHash;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PullRequestReview() { }

    public PullRequestReview(PullRequest pullRequest, User reviewer, PullRequestReviewState state,
            String body, Long reviewedHeadCommitId, String reviewedHeadCommitHash) {
        this.repositoryId = pullRequest.getRepository().getId();
        this.pullRequest = pullRequest;
        this.reviewer = reviewer;
        this.state = state;
        this.body = body;
        this.reviewedHeadCommitId = reviewedHeadCommitId;
        this.reviewedHeadCommitHash = reviewedHeadCommitHash;
    }

    public Long getId() { return id; }
    public Long getRepositoryId() { return repositoryId; }
    public PullRequest getPullRequest() { return pullRequest; }
    public User getReviewer() { return reviewer; }
    public PullRequestReviewState getState() { return state; }
    public String getBody() { return body; }
    public Long getReviewedHeadCommitId() { return reviewedHeadCommitId; }
    public String getReviewedHeadCommitHash() { return reviewedHeadCommitHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
