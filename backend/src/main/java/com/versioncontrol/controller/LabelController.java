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
import com.versioncontrol.dto.label.CreateLabelRequest;
import com.versioncontrol.dto.label.LabelResponse;
import com.versioncontrol.dto.label.UpdateLabelRequest;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.LabelService;

@RestController
public class LabelController {
    private final LabelService labelService;
    public LabelController(LabelService labelService) { this.labelService = labelService; }

    @PostMapping("/api/repositories/{repositoryId}/labels")
    @ResponseStatus(HttpStatus.CREATED)
    public LabelResponse create(@PathVariable Long repositoryId, @Valid @RequestBody CreateLabelRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return labelService.create(repositoryId, request, principal.getId());
    }

    @GetMapping("/api/repositories/{repositoryId}/labels")
    public List<LabelResponse> list(@PathVariable Long repositoryId, @AuthenticationPrincipal UserPrincipal principal) {
        return labelService.list(repositoryId, principal == null ? null : principal.getId());
    }

    @PutMapping("/api/labels/{labelId}")
    public LabelResponse update(@PathVariable Long labelId, @Valid @RequestBody UpdateLabelRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return labelService.update(labelId, request, principal.getId());
    }

    @DeleteMapping("/api/labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long labelId, @AuthenticationPrincipal UserPrincipal principal) {
        labelService.delete(labelId, principal.getId());
    }

    @PostMapping("/api/issues/{issueId}/labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attach(@PathVariable Long issueId, @PathVariable Long labelId, @AuthenticationPrincipal UserPrincipal principal) {
        labelService.attach(issueId, labelId, principal.getId());
    }

    @DeleteMapping("/api/issues/{issueId}/labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void detach(@PathVariable Long issueId, @PathVariable Long labelId, @AuthenticationPrincipal UserPrincipal principal) {
        labelService.detach(issueId, labelId, principal.getId());
    }
}
