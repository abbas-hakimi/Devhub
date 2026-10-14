package com.versioncontrol.service;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.versioncontrol.dto.collaborator.*;
import com.versioncontrol.entity.*;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.RepositoryCollaboratorRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.UserRepository;

@Service
public class CollaboratorService {
    private final RepositoryCollaboratorRepository collaborators;
    private final RepositoryRepository repositories;
    private final UserRepository users;
    private final RepositoryAccessService access;
    private final NotificationService notifications;
    public CollaboratorService(RepositoryCollaboratorRepository collaborators, RepositoryRepository repositories,
            UserRepository users, RepositoryAccessService access, NotificationService notifications) {
        this.collaborators = collaborators; this.repositories = repositories; this.users = users; this.access = access; this.notifications = notifications;
    }

    @Transactional
    public CollaboratorResponse invite(Long repositoryId, CreateInvitationRequest request, Long actorId) {
        Repository repository = repositories.findByIdForUpdate(repositoryId).orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        access.requireMaintain(repository, actorId);
        if (request.role() == CollaboratorRole.MAINTAIN && !access.isOwner(repository, actorId))
            throw new ResourceForbiddenException("Only the repository owner may grant MAINTAIN");
        User invitee = users.findByUsernameIgnoreCase(request.username().trim()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (invitee.getId().equals(repository.getOwner().getId()) || invitee.getId().equals(actorId))
            throw new RepositoryConflictException("Repository owners and the acting user cannot be invited");
        if (collaborators.findByRepository_IdAndUser_Id(repositoryId, invitee.getId()).isPresent())
            throw new RepositoryConflictException("A pending invitation or collaborator already exists");
        User inviter = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        try {
            RepositoryCollaborator invitation = collaborators.saveAndFlush(new RepositoryCollaborator(repository, invitee, request.role(), inviter));
            // A pending invitation itself grants the invitee visibility into this notification.
            notifications.notifyRecipients(List.of(invitee.getId()), actorId, null,
                    com.versioncontrol.entity.NotificationEventType.COLLABORATOR_INVITED, "Repository invitation",
                    "You were invited to collaborate on " + repository.getName(), "REPOSITORY", repositoryId,
                    "COLLABORATOR_INVITED:" + invitation.getId());
            return CollaboratorResponse.from(invitation);
        }
        catch (DataIntegrityViolationException duplicate) { throw new RepositoryConflictException("A pending invitation or collaborator already exists"); }
    }

    @Transactional(readOnly = true)
    public List<CollaboratorResponse> list(Long repositoryId, Long actorId) {
        Repository repository = access.find(repositoryId);
        access.requireMaintain(repository, actorId);
        return collaborators.findByRepository_IdOrderByCreatedAtAsc(repositoryId).stream()
                .map(CollaboratorResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CollaboratorResponse> myInvitations(Long userId) {
        return collaborators.findByUser_IdAndStatusOrderByCreatedAtDesc(userId, InvitationStatus.PENDING).stream().map(CollaboratorResponse::from).toList();
    }

    @Transactional
    public CollaboratorResponse accept(Long invitationId, Long userId) {
        RepositoryCollaborator invitation = collaborators.lockById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
        if (!invitation.getUser().getId().equals(userId)) throw new ResourceNotFoundException("Invitation not found");
        if (invitation.getStatus() != InvitationStatus.PENDING) throw new RepositoryConflictException("Invitation is no longer pending");
        invitation.accept();
        CollaboratorResponse response = CollaboratorResponse.from(collaborators.saveAndFlush(invitation));
        notifications.notifyRecipients(List.of(invitation.getRepository().getOwner().getId()), userId, invitation.getRepository(),
                com.versioncontrol.entity.NotificationEventType.COLLABORATOR_ACCEPTED, "Collaboration invitation accepted",
                "An invitation to " + invitation.getRepository().getName() + " was accepted", "REPOSITORY",
                invitation.getRepository().getId(), "COLLABORATOR_ACCEPTED:" + invitation.getId());
        return response;
    }

    @Transactional
    public void decline(Long invitationId, Long userId) {
        RepositoryCollaborator invitation = collaborators.lockById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
        if (!invitation.getUser().getId().equals(userId)) throw new ResourceNotFoundException("Invitation not found");
        if (invitation.getStatus() != InvitationStatus.PENDING) throw new RepositoryConflictException("Invitation is no longer pending");
        notifications.notifyRecipients(List.of(invitation.getRepository().getOwner().getId()), userId, invitation.getRepository(),
                com.versioncontrol.entity.NotificationEventType.COLLABORATOR_DECLINED, "Collaboration invitation declined",
                "An invitation to " + invitation.getRepository().getName() + " was declined", "REPOSITORY",
                invitation.getRepository().getId(), "COLLABORATOR_DECLINED:" + invitation.getId());
        collaborators.delete(invitation);
    }

    @Transactional
    public void remove(Long repositoryId, Long targetUserId, Long actorId) {
        Repository repository = access.find(repositoryId);
        access.requireMaintain(repository, actorId);
        if (repository.getOwner().getId().equals(targetUserId)) throw new RepositoryConflictException("The repository owner cannot be removed");
        RepositoryCollaborator c = collaborators.findByRepository_IdAndUser_Id(repositoryId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Collaborator or invitation not found"));
        if (access.roleOf(repository, actorId) == CollaboratorRole.MAINTAIN && c.getRole() == CollaboratorRole.MAINTAIN)
            throw new ResourceForbiddenException("Maintainers cannot remove another maintainer");
        collaborators.delete(c);
    }

    @Transactional
    public CollaboratorResponse updateRole(Long repositoryId, Long targetUserId, UpdateCollaboratorRoleRequest request, Long actorId) {
        Repository repository = access.find(repositoryId);
        access.requireMaintain(repository, actorId);
        if (request.role() == CollaboratorRole.MAINTAIN && !access.isOwner(repository, actorId))
            throw new ResourceForbiddenException("Only the repository owner may grant MAINTAIN");
        RepositoryCollaborator c = collaborators.findByRepository_IdAndUser_Id(repositoryId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Collaborator or invitation not found"));
        if (access.roleOf(repository, actorId) == CollaboratorRole.MAINTAIN && c.getRole() == CollaboratorRole.MAINTAIN)
            throw new ResourceForbiddenException("Maintainers cannot change another maintainer's role");
        c.setRole(request.role());
        return CollaboratorResponse.from(collaborators.saveAndFlush(c));
    }
}
