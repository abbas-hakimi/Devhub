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
import com.versioncontrol.dto.comment.CreateIssueCommentRequest;
import com.versioncontrol.dto.comment.IssueCommentResponse;
import com.versioncontrol.dto.comment.UpdateIssueCommentRequest;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.IssueCommentService;

@RestController
public class IssueCommentController {
    private final IssueCommentService commentService;
    public IssueCommentController(IssueCommentService commentService) { this.commentService = commentService; }

    @PostMapping("/api/issues/{issueId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueCommentResponse create(@PathVariable Long issueId, @Valid @RequestBody CreateIssueCommentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return commentService.create(issueId, request, principal.getId());
    }

    @GetMapping("/api/issues/{issueId}/comments")
    public List<IssueCommentResponse> list(@PathVariable Long issueId, @AuthenticationPrincipal UserPrincipal principal) {
        return commentService.list(issueId, principal == null ? null : principal.getId());
    }

    @PutMapping("/api/issues/comments/{commentId}")
    public IssueCommentResponse update(@PathVariable Long commentId, @Valid @RequestBody UpdateIssueCommentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return commentService.update(commentId, request, principal.getId());
    }

    @DeleteMapping("/api/issues/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long commentId, @AuthenticationPrincipal UserPrincipal principal) {
        commentService.delete(commentId, principal.getId());
    }
}
