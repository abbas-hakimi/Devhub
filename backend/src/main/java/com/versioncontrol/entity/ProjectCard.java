package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "project_cards")
public class ProjectCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private Project project;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "column_id", nullable = false) private ProjectColumn column;
    @Column(nullable = false, length = 255) private String title;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(nullable = false) private int position;
    @Enumerated(EnumType.STRING) @Column(name = "card_type", nullable = false, length = 20) private ProjectCardType cardType;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "issue_id") private Issue issue;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pull_request_id") private PullRequest pullRequest;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    protected ProjectCard() { }
    public ProjectCard(Project project, ProjectColumn column, String title, String description, int position,
            ProjectCardType cardType, Issue issue, PullRequest pullRequest) {
        this.project = project; this.column = column; this.title = title; this.description = description;
        this.position = position; this.cardType = cardType; this.issue = issue; this.pullRequest = pullRequest;
    }
    public Long getId() { return id; }
    public Project getProject() { return project; }
    public ProjectColumn getColumn() { return column; }
    public void setColumn(ProjectColumn column) { this.column = column; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public ProjectCardType getCardType() { return cardType; }
    public Issue getIssue() { return issue; }
    public PullRequest getPullRequest() { return pullRequest; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
