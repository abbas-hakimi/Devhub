package com.versioncontrol.dto.collaborator;

import com.versioncontrol.entity.CollaboratorRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInvitationRequest(@NotBlank @Size(max = 50) String username, @NotNull CollaboratorRole role) { }
