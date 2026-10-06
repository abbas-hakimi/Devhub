package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "issue_comments")
public class IssueComment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id", nullable = false)
    private User author;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    protected IssueComment() { }
    public IssueComment(Issue issue, User author, String body) { this.issue = issue; this.author = author; this.body = body; }
    public Long getId() { return id; }
    public Issue getIssue() { return issue; }
    public User getAuthor() { return author; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
