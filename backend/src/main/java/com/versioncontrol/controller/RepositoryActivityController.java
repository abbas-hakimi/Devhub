package com.versioncontrol.controller;

import com.versioncontrol.dto.activity.RepositoryActivityPageResponse;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.RepositoryActivityService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class RepositoryActivityController {
    private final RepositoryActivityService activity;
    public RepositoryActivityController(RepositoryActivityService activity) { this.activity = activity; }
    @GetMapping("/api/repositories/{repositoryId}/activity")
    public RepositoryActivityPageResponse list(@PathVariable Long repositoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return activity.list(repositoryId, page, size, principal == null ? null : principal.getId());
    }
}
