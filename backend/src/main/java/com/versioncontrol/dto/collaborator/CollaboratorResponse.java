package com.versioncontrol.dto.collaborator;

import java.time.LocalDateTime;
import com.versioncontrol.entity.RepositoryCollaborator;
import com.versioncontrol.entity.CollaboratorRole;
import com.versioncontrol.entity.InvitationStatus;

public record CollaboratorResponse(Long invitationId, Long repositoryId, Long userId, String username,
        CollaboratorRole role, InvitationStatus status, Long invitedById, String invitedByUsername,
        LocalDateTime createdAt, LocalDateTime acceptedAt, LocalDateTime updatedAt) {
    public static CollaboratorResponse from(RepositoryCollaborator c) {
        return new CollaboratorResponse(c.getId(), c.getRepository().getId(), c.getUser().getId(), c.getUser().getUsername(),
                c.getRole(), c.getStatus(), c.getInvitedBy().getId(), c.getInvitedBy().getUsername(), c.getCreatedAt(), c.getAcceptedAt(), c.getUpdatedAt());
    }
}
