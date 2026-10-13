package com.versioncontrol.controller;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.versioncontrol.dto.collaborator.*;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.CollaboratorService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class CollaboratorController {
    private final CollaboratorService service;
    public CollaboratorController(CollaboratorService service) { this.service = service; }

    @PostMapping("/repositories/{repositoryId}/collaborators/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public CollaboratorResponse invite(@PathVariable Long repositoryId, @Valid @RequestBody CreateInvitationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return service.invite(repositoryId, request, principal.getId()); }

    @GetMapping("/repositories/{repositoryId}/collaborators")
    public List<CollaboratorResponse> list(@PathVariable Long repositoryId, @AuthenticationPrincipal UserPrincipal principal) {
        return service.list(repositoryId, principal.getId());
    }

    @GetMapping("/me/collaboration-invitations")
    public List<CollaboratorResponse> myInvitations(@AuthenticationPrincipal UserPrincipal principal) { return service.myInvitations(principal.getId()); }

    @PostMapping("/collaboration-invitations/{invitationId}/accept")
    public CollaboratorResponse accept(@PathVariable Long invitationId, @AuthenticationPrincipal UserPrincipal principal) {
        return service.accept(invitationId, principal.getId());
    }

    @PostMapping("/collaboration-invitations/{invitationId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable Long invitationId, @AuthenticationPrincipal UserPrincipal principal) { service.decline(invitationId, principal.getId()); }

    @DeleteMapping("/repositories/{repositoryId}/collaborators/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long repositoryId, @PathVariable Long userId, @AuthenticationPrincipal UserPrincipal principal) {
        service.remove(repositoryId, userId, principal.getId());
    }

    @PutMapping("/repositories/{repositoryId}/collaborators/{userId}/role")
    public CollaboratorResponse updateRole(@PathVariable Long repositoryId, @PathVariable Long userId,
            @Valid @RequestBody UpdateCollaboratorRoleRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return service.updateRole(repositoryId, userId, request, principal.getId());
    }
}
