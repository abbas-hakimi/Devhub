package com.versioncontrol.controller;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import com.versioncontrol.dto.repository.CreateRepositoryRequest;
import com.versioncontrol.dto.repository.RepositoryResponse;
import com.versioncontrol.dto.repository.UpdateRepositoryRequest;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.RepositoryService;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final RepositoryService repositoryService;

    public RepositoryController(RepositoryService repositoryService) { this.repositoryService = repositoryService; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryResponse create(@Valid @RequestBody CreateRepositoryRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return repositoryService.create(request, principal.getId());
    }

    @GetMapping
    public List<RepositoryResponse> listPublic(@RequestParam(required = false) String owner, @RequestParam(required = false) String name) {
        return repositoryService.listPublic(owner, name);
    }

    @GetMapping("/mine")
    public List<RepositoryResponse> mine(@AuthenticationPrincipal UserPrincipal principal) {
        return repositoryService.listMine(principal.getId());
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return repositoryService.get(id, principal == null ? null : principal.getId());
    }

    @PutMapping("/{id}")
    public RepositoryResponse update(@PathVariable Long id, @Valid @RequestBody UpdateRepositoryRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return repositoryService.update(id, request, principal.getId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        repositoryService.delete(id, principal.getId());
    }
}
