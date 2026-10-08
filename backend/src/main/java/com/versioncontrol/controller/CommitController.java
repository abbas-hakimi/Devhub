package com.versioncontrol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.versioncontrol.dto.git.CommitResponse;
import com.versioncontrol.dto.git.CreateCommitRequest;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.CommitService;

import jakarta.validation.Valid;

@RestController
public class CommitController {
    private final CommitService commitService;

    public CommitController(CommitService commitService) { this.commitService = commitService; }

    @PostMapping("/api/repositories/{repositoryId}/commits")
    @ResponseStatus(HttpStatus.CREATED)
    public CommitResponse create(@PathVariable Long repositoryId, @Valid @RequestBody CreateCommitRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return commitService.create(repositoryId, request, principal.getId());
    }
}
