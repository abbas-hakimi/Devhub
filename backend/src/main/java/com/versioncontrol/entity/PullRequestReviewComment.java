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
@Table(name = "pull_request_review_comments", indexes = {
        @Index(name = "idx_pull_request_review_comments_pr_created", columnList = "pull_request_id, created_at, id"),
        @Index(name = "idx_pull_request_review_comments_review", columnList = "review_id, id"),
        @Index(name = "idx_pull_request_review_comments_author", columnList = "author_id")
})
public class PullRequestReviewComment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "pull_request_id", nullable = false)
    private Long pullRequestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private PullRequestReview review;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = 1024)
    private String path;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private ReviewCommentSide side;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PullRequestReviewComment() { }

    public PullRequestReviewComment(PullRequestReview review, User author, String path,
            Integer lineNumber, ReviewCommentSide side, String body) {
        this.repositoryId = review.getRepositoryId();
        this.pullRequestId = review.getPullRequest().getId();
        this.review = review;
        this.author = author;
        this.path = path;
        this.lineNumber = lineNumber;
        this.side = side;
        this.body = body;
    }

    public Long getId() { return id; }
    public Long getRepositoryId() { return repositoryId; }
    public Long getPullRequestId() { return pullRequestId; }
    public PullRequestReview getReview() { return review; }
    public User getAuthor() { return author; }
    public String getPath() { return path; }
    public Integer getLineNumber() { return lineNumber; }
    public ReviewCommentSide getSide() { return side; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
