package com.versioncontrol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.versioncontrol.dto.discussion.*;
import com.versioncontrol.entity.DiscussionReactionType;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.DiscussionReplyService;
import jakarta.validation.Valid;

@RestController
public class DiscussionReplyController {
    private final DiscussionReplyService replies;
    public DiscussionReplyController(DiscussionReplyService replies) { this.replies = replies; }
    @GetMapping("/api/discussions/{discussionId}/replies")
    public DiscussionReplyPageResponse list(@PathVariable Long discussionId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal UserPrincipal principal) {
        return replies.list(discussionId, page, size, principal == null ? null : principal.getId());
    }
    @PostMapping("/api/discussions/{discussionId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscussionReplyResponse create(@PathVariable Long discussionId, @Valid @RequestBody CreateReplyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return replies.create(discussionId, request, principal.getId());
    }
    @PatchMapping("/api/discussion-replies/{replyId}")
    public DiscussionReplyResponse update(@PathVariable Long replyId, @Valid @RequestBody UpdateReplyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return replies.update(replyId, request, principal.getId());
    }
    @DeleteMapping("/api/discussion-replies/{replyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long replyId, @AuthenticationPrincipal UserPrincipal principal) {
        replies.delete(replyId, principal.getId());
    }
    @PostMapping("/api/discussion-replies/{replyId}/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addReaction(@PathVariable Long replyId, @Valid @RequestBody ReactionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        replies.addReaction(replyId, request.type(), principal.getId());
    }
    @DeleteMapping("/api/discussion-replies/{replyId}/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeReaction(@PathVariable Long replyId, @RequestParam DiscussionReactionType type,
            @AuthenticationPrincipal UserPrincipal principal) {
        replies.removeReaction(replyId, type, principal.getId());
    }
}
