package com.versioncontrol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.versioncontrol.dto.release.CreateReleaseRequest;
import com.versioncontrol.dto.release.RepositoryReleasePageResponse;
import com.versioncontrol.dto.release.RepositoryReleaseResponse;
import com.versioncontrol.dto.release.UpdateReleaseRequest;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.RepositoryReleaseService;

import jakarta.validation.Valid;

@RestController
public class RepositoryReleaseController {
    private final RepositoryReleaseService releases;
    public RepositoryReleaseController(RepositoryReleaseService releases) { this.releases = releases; }

    @GetMapping("/api/repositories/{repositoryId}/releases")
    public RepositoryReleasePageResponse list(@PathVariable Long repositoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return releases.list(repositoryId, page, size, userId(principal));
    }

    @GetMapping("/api/repositories/{repositoryId}/releases/{releaseId}")
    public RepositoryReleaseResponse get(@PathVariable Long repositoryId, @PathVariable Long releaseId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return releases.get(repositoryId, releaseId, userId(principal));
    }

    @PostMapping("/api/repositories/{repositoryId}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryReleaseResponse create(@PathVariable Long repositoryId,
            @Valid @RequestBody CreateReleaseRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return releases.create(repositoryId, request, principal.getId());
    }

    @PatchMapping("/api/repositories/{repositoryId}/releases/{releaseId}")
    public RepositoryReleaseResponse update(@PathVariable Long repositoryId, @PathVariable Long releaseId,
            @Valid @RequestBody UpdateReleaseRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return releases.update(repositoryId, releaseId, request, principal.getId());
    }

    @DeleteMapping("/api/repositories/{repositoryId}/releases/{releaseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long repositoryId, @PathVariable Long releaseId,
            @AuthenticationPrincipal UserPrincipal principal) {
        releases.delete(repositoryId, releaseId, principal.getId());
    }

    private Long userId(UserPrincipal principal) { return principal == null ? null : principal.getId(); }
}
