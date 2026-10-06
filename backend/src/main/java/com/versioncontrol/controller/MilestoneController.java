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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import com.versioncontrol.dto.milestone.CreateMilestoneRequest;
import com.versioncontrol.dto.milestone.MilestoneResponse;
import com.versioncontrol.dto.milestone.UpdateMilestoneRequest;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.MilestoneService;

@RestController
public class MilestoneController {
    private final MilestoneService milestoneService;
    public MilestoneController(MilestoneService milestoneService) { this.milestoneService = milestoneService; }

    @PostMapping("/api/repositories/{repositoryId}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    public MilestoneResponse create(@PathVariable Long repositoryId, @Valid @RequestBody CreateMilestoneRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return milestoneService.create(repositoryId, request, principal.getId());
    }

    @GetMapping("/api/repositories/{repositoryId}/milestones")
    public List<MilestoneResponse> list(@PathVariable Long repositoryId, @AuthenticationPrincipal UserPrincipal principal) {
        return milestoneService.list(repositoryId, principal == null ? null : principal.getId());
    }

    @GetMapping("/api/milestones/{milestoneId}")
    public MilestoneResponse get(@PathVariable Long milestoneId, @AuthenticationPrincipal UserPrincipal principal) {
        return milestoneService.get(milestoneId, principal == null ? null : principal.getId());
    }

    @PutMapping("/api/milestones/{milestoneId}")
    public MilestoneResponse update(@PathVariable Long milestoneId, @RequestBody UpdateMilestoneRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return milestoneService.update(milestoneId, request, principal.getId());
    }

    @DeleteMapping("/api/milestones/{milestoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long milestoneId, @AuthenticationPrincipal UserPrincipal principal) {
        milestoneService.delete(milestoneId, principal.getId());
    }
}
