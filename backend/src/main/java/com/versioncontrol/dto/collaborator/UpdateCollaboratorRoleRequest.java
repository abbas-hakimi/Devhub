package com.versioncontrol.dto.collaborator;

import com.versioncontrol.entity.CollaboratorRole;
import jakarta.validation.constraints.NotNull;

public record UpdateCollaboratorRoleRequest(@NotNull CollaboratorRole role) { }
