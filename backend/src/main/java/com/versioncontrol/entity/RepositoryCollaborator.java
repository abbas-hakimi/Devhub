package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.*;

@Entity
@Table(name = "repository_collaborators", uniqueConstraints = @UniqueConstraint(name = "uk_repository_collaborators_repository_user", columnNames = {"repository_id", "user_id"}), indexes = {
        @Index(name = "idx_repository_collaborators_user_status", columnList = "user_id, status, created_at"),
        @Index(name = "idx_repository_collaborators_repository_status", columnList = "repository_id, status, role")})
public class RepositoryCollaborator {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private Repository repository;
    @Column(name = "repository_owner_id", nullable = false, updatable = false) private Long repositoryOwnerId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CollaboratorRole role;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private InvitationStatus status;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "invited_by_id", nullable = false) private User invitedBy;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "accepted_at") private LocalDateTime acceptedAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected RepositoryCollaborator() { }
    public RepositoryCollaborator(Repository repository, User user, CollaboratorRole role, User invitedBy) {
        this.repository = repository; this.repositoryOwnerId = repository.getOwner().getId();
        this.user = user; this.role = role; this.invitedBy = invitedBy; this.status = InvitationStatus.PENDING;
    }
    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public User getUser() { return user; }
    public CollaboratorRole getRole() { return role; }
    public void setRole(CollaboratorRole role) { this.role = role; }
    public InvitationStatus getStatus() { return status; }
    public void accept() { this.status = InvitationStatus.ACCEPTED; this.acceptedAt = LocalDateTime.now(); }
    public User getInvitedBy() { return invitedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
