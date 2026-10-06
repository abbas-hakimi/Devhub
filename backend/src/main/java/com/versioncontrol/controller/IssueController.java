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
import com.versioncontrol.dto.issue.CreateIssueRequest;
import com.versioncontrol.dto.issue.IssueResponse;
import com.versioncontrol.dto.issue.UpdateIssueRequest;
import com.versioncontrol.entity.IssueStatus;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.IssueService;

@RestController
public class IssueController {
    private final IssueService issueService;
    public IssueController(IssueService issueService) { this.issueService = issueService; }

    @PostMapping("/api/repositories/{repositoryId}/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse create(@PathVariable Long repositoryId, @Valid @RequestBody CreateIssueRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return issueService.create(repositoryId, request, principal.getId());
    }

    @GetMapping("/api/repositories/{repositoryId}/issues")
    public List<IssueResponse> list(@PathVariable Long repositoryId, @RequestParam(required = false) IssueStatus status,
            @AuthenticationPrincipal UserPrincipal principal) {
        return issueService.list(repositoryId, status, principal == null ? null : principal.getId());
    }

    @GetMapping("/api/issues/{issueId}")
    public IssueResponse get(@PathVariable Long issueId, @AuthenticationPrincipal UserPrincipal principal) {
        return issueService.get(issueId, principal == null ? null : principal.getId());
    }

    @PutMapping("/api/issues/{issueId}")
    public IssueResponse update(@PathVariable Long issueId, @Valid @RequestBody UpdateIssueRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return issueService.update(issueId, request, principal.getId());
    }

    @DeleteMapping("/api/issues/{issueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long issueId, @AuthenticationPrincipal UserPrincipal principal) {
        issueService.delete(issueId, principal.getId());
    }
}
