package com.versioncontrol.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import com.versioncontrol.dto.git.BranchResponse;
import com.versioncontrol.dto.git.CreateBranchRequest;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.BranchService;

@RestController
public class BranchController {
    private final BranchService branchService;

    public BranchController(BranchService branchService) { this.branchService = branchService; }

    @PostMapping("/api/repositories/{repositoryId}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResponse create(@PathVariable Long repositoryId, @Valid @RequestBody CreateBranchRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return branchService.create(repositoryId, request, principal.getId());
    }

    @GetMapping("/api/repositories/{repositoryId}/branches")
    public List<BranchResponse> list(@PathVariable Long repositoryId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return branchService.list(repositoryId, principal == null ? null : principal.getId());
    }

    @GetMapping("/api/branches/{branchId}")
    public BranchResponse get(@PathVariable Long branchId, @AuthenticationPrincipal UserPrincipal principal) {
        return branchService.get(branchId, principal == null ? null : principal.getId());
    }
}
