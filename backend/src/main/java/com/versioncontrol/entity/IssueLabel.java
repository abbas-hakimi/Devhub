package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "issue_labels", uniqueConstraints = @UniqueConstraint(name = "uk_issue_labels_issue_label", columnNames = {"issue_id", "label_id"}))
public class IssueLabel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "label_id", nullable = false)
    private Label label;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    protected IssueLabel() { }
    public IssueLabel(Issue issue, Label label) { this.issue = issue; this.label = label; }
    public Long getId() { return id; }
    public Issue getIssue() { return issue; }
    public Label getLabel() { return label; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
