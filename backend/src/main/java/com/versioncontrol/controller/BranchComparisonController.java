package com.versioncontrol.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.versioncontrol.dto.diff.ComparisonResponse;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.BranchComparisonService;

@RestController
public class BranchComparisonController {
    private final BranchComparisonService comparisons;
    public BranchComparisonController(BranchComparisonService comparisons) { this.comparisons = comparisons; }

    @GetMapping("/api/repositories/{repositoryId}/compare")
    public ComparisonResponse compare(@PathVariable Long repositoryId,
            @RequestParam String base, @RequestParam String head,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return comparisons.compareBranches(repositoryId, base, head, page, size, userId(principal));
    }

    @GetMapping("/api/pull-requests/{pullRequestId}/diff")
    public ComparisonResponse pullRequestDiff(@PathVariable Long pullRequestId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return comparisons.comparePullRequest(pullRequestId, page, size, userId(principal));
    }

    private Long userId(UserPrincipal principal) { return principal == null ? null : principal.getId(); }
}
