package com.versioncontrol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.versioncontrol.dto.discussion.*;
import com.versioncontrol.entity.DiscussionReactionType;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.DiscussionService;
import jakarta.validation.Valid;

@RestController
public class DiscussionController {
    private final DiscussionService discussions;
    public DiscussionController(DiscussionService discussions) { this.discussions = discussions; }

    @GetMapping("/api/repositories/{repositoryId}/discussions")
    public DiscussionPageResponse list(@PathVariable Long repositoryId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal UserPrincipal principal) {
        return discussions.list(repositoryId, page, size, id(principal));
    }
    @PostMapping("/api/repositories/{repositoryId}/discussions")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscussionResponse create(@PathVariable Long repositoryId, @Valid @RequestBody CreateDiscussionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return discussions.create(repositoryId, request, principal.getId());
    }
    @GetMapping("/api/discussions/{discussionId}")
    public DiscussionResponse get(@PathVariable Long discussionId, @AuthenticationPrincipal UserPrincipal principal) {
        return discussions.get(discussionId, id(principal));
    }
    @PatchMapping("/api/discussions/{discussionId}")
    public DiscussionResponse update(@PathVariable Long discussionId, @RequestBody UpdateDiscussionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return discussions.update(discussionId, request, principal.getId());
    }
    @PostMapping("/api/discussions/{discussionId}/close")
    public DiscussionResponse close(@PathVariable Long discussionId, @AuthenticationPrincipal UserPrincipal principal) {
        return discussions.close(discussionId, principal.getId());
    }
    @PostMapping("/api/discussions/{discussionId}/reopen")
    public DiscussionResponse reopen(@PathVariable Long discussionId, @AuthenticationPrincipal UserPrincipal principal) {
        return discussions.reopen(discussionId, principal.getId());
    }
    @DeleteMapping("/api/discussions/{discussionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long discussionId, @AuthenticationPrincipal UserPrincipal principal) {
        discussions.delete(discussionId, principal.getId());
    }
    @PutMapping("/api/discussions/{discussionId}/accepted-answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptAnswer(@PathVariable Long discussionId, @Valid @RequestBody AcceptedAnswerRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        discussions.acceptAnswer(discussionId, request.replyId(), principal.getId());
    }
    @DeleteMapping("/api/discussions/{discussionId}/accepted-answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearAcceptedAnswer(@PathVariable Long discussionId, @AuthenticationPrincipal UserPrincipal principal) {
        discussions.clearAcceptedAnswer(discussionId, principal.getId());
    }
    @PostMapping("/api/discussions/{discussionId}/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addReaction(@PathVariable Long discussionId, @Valid @RequestBody ReactionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        discussions.addReaction(discussionId, request.type(), principal.getId());
    }
    @DeleteMapping("/api/discussions/{discussionId}/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeReaction(@PathVariable Long discussionId, @RequestParam DiscussionReactionType type,
            @AuthenticationPrincipal UserPrincipal principal) {
        discussions.removeReaction(discussionId, type, principal.getId());
    }
    private Long id(UserPrincipal p) { return p == null ? null : p.getId(); }
}
