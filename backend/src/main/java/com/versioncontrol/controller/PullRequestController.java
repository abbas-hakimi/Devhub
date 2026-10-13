package com.versioncontrol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.versioncontrol.dto.pullrequest.CreatePullRequestRequest;
import com.versioncontrol.dto.pullrequest.PullRequestPageResponse;
import com.versioncontrol.dto.pullrequest.PullRequestResponse;
import com.versioncontrol.dto.pullrequest.UpdatePullRequestRequest;
import com.versioncontrol.entity.PullRequestState;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.PullRequestService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class PullRequestController {
    private final PullRequestService service;
    public PullRequestController(PullRequestService service) { this.service = service; }

    @PostMapping("/repositories/{repositoryId}/pull-requests")
    public ResponseEntity<PullRequestResponse> create(@PathVariable Long repositoryId,
            @Valid @RequestBody CreatePullRequestRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(repositoryId, request, userId(authentication)));
    }

    @GetMapping("/repositories/{repositoryId}/pull-requests")
    public PullRequestPageResponse list(@PathVariable Long repositoryId,
            @RequestParam(required = false) PullRequestState state,
            @RequestParam(required = false) Long authorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return service.list(repositoryId, state, authorId, page, size, userId(authentication));
    }

    @GetMapping("/pull-requests/{pullRequestId}")
    public PullRequestResponse get(@PathVariable Long pullRequestId, Authentication authentication) {
        return service.get(pullRequestId, userId(authentication));
    }

    @PutMapping("/pull-requests/{pullRequestId}")
    public PullRequestResponse update(@PathVariable Long pullRequestId,
            @RequestBody UpdatePullRequestRequest request, Authentication authentication) {
        return service.update(pullRequestId, request, userId(authentication));
    }

    @PostMapping("/pull-requests/{pullRequestId}/close")
    public PullRequestResponse close(@PathVariable Long pullRequestId, Authentication authentication) {
        return service.close(pullRequestId, userId(authentication));
    }

    @PostMapping("/pull-requests/{pullRequestId}/reopen")
    public PullRequestResponse reopen(@PathVariable Long pullRequestId, Authentication authentication) {
        return service.reopen(pullRequestId, userId(authentication));
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) return null;
        return principal.getId();
    }
}
