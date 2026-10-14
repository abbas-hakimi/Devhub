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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "repository_releases", indexes = @Index(name = "idx_repository_releases_repository_publication",
        columnList = "repository_id, draft, published_at, id"), uniqueConstraints = {
        @UniqueConstraint(name = "uk_repository_releases_repository_tag", columnNames = {"repository_id", "tag_id"}),
        @UniqueConstraint(name = "uk_repository_releases_repository_id_id", columnNames = {"repository_id", "id"})
})
public class RepositoryRelease {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", referencedColumnName = "id", insertable = false, updatable = false)
    private RepositoryTag tag;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean draft;

    @Column(nullable = false)
    private boolean prerelease;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    protected RepositoryRelease() { }

    public RepositoryRelease(Repository repository, RepositoryTag tag, String title, String description,
            boolean draft, boolean prerelease, User createdBy, LocalDateTime publishedAt) {
        if (repository == null || tag == null || createdBy == null || title == null || title.isBlank()) {
            throw new IllegalArgumentException("repository, tag, title, and creator are required");
        }
        if (repository.getId() == null || !repository.getId().equals(tag.getRepository().getId())) {
            throw new IllegalArgumentException("release tag must belong to its repository");
        }
        if (draft == (publishedAt != null)) {
            throw new IllegalArgumentException("published timestamp must be present exactly when the release is published");
        }
        this.repository = repository;
        this.tag = tag;
        this.tagId = tag.getId();
        this.title = title;
        this.description = description;
        this.draft = draft;
        this.prerelease = prerelease;
        this.createdBy = createdBy;
        this.publishedAt = publishedAt;
    }

    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public Long getTagId() { return tagId; }
    public RepositoryTag getTag() { return tag; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isDraft() { return draft; }
    public void setDraft(boolean draft) { this.draft = draft; }
    public boolean isPrerelease() { return prerelease; }
    public void setPrerelease(boolean prerelease) { this.prerelease = prerelease; }
    public User getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
