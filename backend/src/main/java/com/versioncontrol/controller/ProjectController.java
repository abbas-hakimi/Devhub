package com.versioncontrol.controller;

import com.versioncontrol.dto.project.*;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.ProjectManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class ProjectController {
    private final ProjectManagementService projects;
    public ProjectController(ProjectManagementService projects) { this.projects = projects; }

    @PostMapping("/api/projects") @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return projects.create(request, principal.getId());
    }
    @GetMapping("/api/projects")
    public ProjectPageResponse list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.list(page, size, id(principal)); }
    @GetMapping("/api/projects/{projectId}")
    public ProjectResponse get(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal principal) { return projects.get(projectId, id(principal)); }
    @PatchMapping("/api/projects/{projectId}")
    public ProjectResponse update(@PathVariable Long projectId, @Valid @RequestBody UpdateProjectRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.update(projectId, request, principal.getId()); }
    @DeleteMapping("/api/projects/{projectId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal principal) { projects.delete(projectId, principal.getId()); }
    @PostMapping("/api/projects/{projectId}/archive")
    public ProjectResponse archive(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal principal) { return projects.archive(projectId, principal.getId(), false); }
    @PostMapping("/api/projects/{projectId}/restore")
    public ProjectResponse restore(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal principal) { return projects.archive(projectId, principal.getId(), true); }

    @GetMapping("/api/projects/{projectId}/columns")
    public List<ProjectColumnResponse> listColumns(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal principal) { return projects.listColumns(projectId, id(principal)); }
    @PostMapping("/api/projects/{projectId}/columns") @ResponseStatus(HttpStatus.CREATED)
    public ProjectColumnResponse addColumn(@PathVariable Long projectId, @Valid @RequestBody CreateProjectColumnRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.addColumn(projectId, request, principal.getId()); }
    @PatchMapping("/api/project-columns/{columnId}")
    public ProjectColumnResponse renameColumn(@PathVariable Long columnId, @Valid @RequestBody UpdateProjectColumnRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.renameColumn(columnId, request, principal.getId()); }
    @DeleteMapping("/api/project-columns/{columnId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteColumn(@PathVariable Long columnId, @AuthenticationPrincipal UserPrincipal principal) { projects.deleteColumn(columnId, principal.getId()); }
    @PutMapping("/api/projects/{projectId}/columns/order")
    public List<ProjectColumnResponse> reorderColumns(@PathVariable Long projectId, @Valid @RequestBody ColumnOrderRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.reorderColumns(projectId, request, principal.getId()); }

    @PostMapping("/api/projects/{projectId}/cards") @ResponseStatus(HttpStatus.CREATED)
    public ProjectCardResponse createCard(@PathVariable Long projectId, @Valid @RequestBody CreateProjectCardRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.createCard(projectId, request, principal.getId()); }
    @GetMapping("/api/projects/{projectId}/cards")
    public ProjectCardPageResponse listCards(@PathVariable Long projectId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, @AuthenticationPrincipal UserPrincipal principal) {
        return projects.listCards(projectId, page, size, id(principal));
    }
    @GetMapping("/api/projects/{projectId}/board")
    public ProjectBoardResponse board(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal principal) { return projects.board(projectId, id(principal)); }
    @PatchMapping("/api/project-cards/{cardId}")
    public ProjectCardResponse updateCard(@PathVariable Long cardId, @Valid @RequestBody UpdateProjectCardRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.updateCard(cardId, request, principal.getId()); }
    @DeleteMapping("/api/project-cards/{cardId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(@PathVariable Long cardId, @AuthenticationPrincipal UserPrincipal principal) { projects.deleteCard(cardId, principal.getId()); }
    @PostMapping("/api/project-cards/{cardId}/move")
    public ProjectCardResponse moveCard(@PathVariable Long cardId, @Valid @RequestBody MoveProjectCardRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.moveCard(cardId, request, principal.getId()); }
    @PutMapping("/api/projects/{projectId}/cards/order")
    public List<ProjectCardResponse> reorderCards(@PathVariable Long projectId, @Valid @RequestBody CardOrderRequest request,
            @AuthenticationPrincipal UserPrincipal principal) { return projects.reorderCards(projectId, request, principal.getId()); }

    private Long id(UserPrincipal principal) { return principal == null ? null : principal.getId(); }
}
