package com.versioncontrol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.versioncontrol.dto.tag.CreateTagRequest;
import com.versioncontrol.dto.tag.RepositoryTagPageResponse;
import com.versioncontrol.dto.tag.RepositoryTagResponse;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.RepositoryTagService;

import jakarta.validation.Valid;

@RestController
public class RepositoryTagController {
    private final RepositoryTagService tags;
    public RepositoryTagController(RepositoryTagService tags) { this.tags = tags; }

    @GetMapping("/api/repositories/{repositoryId}/tags")
    public RepositoryTagPageResponse list(@PathVariable Long repositoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return tags.list(repositoryId, page, size, userId(principal));
    }

    @GetMapping("/api/repositories/{repositoryId}/tags/{tagName}")
    public RepositoryTagResponse get(@PathVariable Long repositoryId, @PathVariable String tagName,
            @AuthenticationPrincipal UserPrincipal principal) {
        return tags.get(repositoryId, tagName, userId(principal));
    }

    @PostMapping("/api/repositories/{repositoryId}/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryTagResponse create(@PathVariable Long repositoryId, @Valid @RequestBody CreateTagRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return tags.create(repositoryId, request, principal.getId());
    }

    @DeleteMapping("/api/repositories/{repositoryId}/tags/{tagName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long repositoryId, @PathVariable String tagName,
            @AuthenticationPrincipal UserPrincipal principal) {
        tags.delete(repositoryId, tagName, principal.getId());
    }

    private Long userId(UserPrincipal principal) { return principal == null ? null : principal.getId(); }
}
