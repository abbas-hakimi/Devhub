package com.versioncontrol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.versioncontrol.dto.git.GitInitializationResponse;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.GitInitializationResult;
import com.versioncontrol.service.GitInitializationService;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/git")
public class GitInitializationController {
    private final GitInitializationService initializationService;

    public GitInitializationController(GitInitializationService initializationService) {
        this.initializationService = initializationService;
    }

    @PostMapping("/init")
    public ResponseEntity<GitInitializationResponse> initialize(@PathVariable Long repositoryId,
            @AuthenticationPrincipal UserPrincipal principal) {
        GitInitializationResult result = initializationService.initialize(repositoryId, principal.getId());
        return ResponseEntity.status(result.initializedNow() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }
}
