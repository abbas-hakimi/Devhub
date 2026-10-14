package com.versioncontrol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.versioncontrol.dto.pullrequest.CreatePullRequestRequest;
import com.versioncontrol.dto.pullrequest.PullRequestPageResponse;
import com.versioncontrol.dto.pullrequest.PullRequestResponse;
import com.versioncontrol.dto.pullrequest.UpdatePullRequestRequest;
import com.versioncontrol.dto.pullrequest.MergePullRequestRequest;
import com.versioncontrol.dto.pullrequest.MergePullRequestResponse;
import com.versioncontrol.dto.pullrequest.MergeabilityResponse;
import com.versioncontrol.dto.review.CreateStandaloneReviewCommentRequest;
import com.versioncontrol.dto.review.PullRequestReviewResponse;
import com.versioncontrol.dto.review.ReviewCommentPageResponse;
import com.versioncontrol.dto.review.ReviewCommentResponse;
import com.versioncontrol.dto.review.ReviewPageResponse;
import com.versioncontrol.dto.review.ReviewStatusResponse;
import com.versioncontrol.dto.review.SubmitPullRequestReviewRequest;
import com.versioncontrol.dto.review.UpdateReviewCommentRequest;
import com.versioncontrol.entity.PullRequestState;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.PullRequestService;
import com.versioncontrol.service.PullRequestReviewService;
import com.versioncontrol.service.PullRequestMergeService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api")
public class PullRequestController {
    private final PullRequestService service;
    private final PullRequestReviewService reviewService;
    private final PullRequestMergeService mergeService;
    public PullRequestController(PullRequestService service, PullRequestReviewService reviewService,
            PullRequestMergeService mergeService) {
        this.service = service;
        this.reviewService = reviewService;
        this.mergeService = mergeService;
    }

    @PostMapping("/repositories/{repositoryId}/pull-requests")
    public ResponseEntity<PullRequestResponse> create(@PathVariable Long repositoryId,
            @Valid @RequestBody CreatePullRequestRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(repositoryId, request, userId(authentication)));
    }

    @GetMapping("/repositories/{repositoryId}/pull-requests")
    public PullRequestPageResponse list(@PathVariable Long repositoryId,
            @RequestParam(required = false) PullRequestState state,
            @RequestParam(required = false) Long authorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return service.list(repositoryId, state, authorId, page, size, userId(authentication));
    }

    @GetMapping("/pull-requests/{pullRequestId}")
    public PullRequestResponse get(@PathVariable Long pullRequestId, Authentication authentication) {
        return service.get(pullRequestId, userId(authentication));
    }

    @PutMapping("/pull-requests/{pullRequestId}")
    public PullRequestResponse update(@PathVariable Long pullRequestId,
            @RequestBody UpdatePullRequestRequest request, Authentication authentication) {
        return service.update(pullRequestId, request, userId(authentication));
    }

    @PostMapping("/pull-requests/{pullRequestId}/close")
    public PullRequestResponse close(@PathVariable Long pullRequestId, Authentication authentication) {
        return service.close(pullRequestId, userId(authentication));
    }

    @PostMapping("/pull-requests/{pullRequestId}/reopen")
    public PullRequestResponse reopen(@PathVariable Long pullRequestId, Authentication authentication) {
        return service.reopen(pullRequestId, userId(authentication));
    }

    @PostMapping("/pull-requests/{pullRequestId}/reviews")
    public ResponseEntity<PullRequestReviewResponse> submitReview(@PathVariable Long pullRequestId,
            @Valid @RequestBody SubmitPullRequestReviewRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.submit(pullRequestId, request, userId(authentication)));
    }

    @GetMapping("/pull-requests/{pullRequestId}/reviews")
    public ReviewPageResponse reviews(@PathVariable Long pullRequestId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return reviewService.list(pullRequestId, page, size, userId(authentication));
    }

    @GetMapping("/pull-requests/{pullRequestId}/review-status")
    public ReviewStatusResponse reviewStatus(@PathVariable Long pullRequestId, Authentication authentication) {
        return reviewService.status(pullRequestId, userId(authentication));
    }

    @GetMapping("/pull-requests/{pullRequestId}/mergeability")
    public MergeabilityResponse mergeability(@PathVariable Long pullRequestId, Authentication authentication) {
        return mergeService.mergeability(pullRequestId, userId(authentication));
    }

    @PostMapping("/pull-requests/{pullRequestId}/merge")
    public ResponseEntity<MergePullRequestResponse> merge(@PathVariable Long pullRequestId,
            @Valid @RequestBody MergePullRequestRequest request, Authentication authentication) {
        MergePullRequestResponse response = mergeService.merge(pullRequestId, request, userId(authentication));
        return response.conflictPaths().isEmpty()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @GetMapping("/pull-requests/{pullRequestId}/comments")
    public ReviewCommentPageResponse comments(@PathVariable Long pullRequestId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return reviewService.listComments(pullRequestId, page, size, userId(authentication));
    }

    @PostMapping("/pull-requests/{pullRequestId}/comments")
    public ResponseEntity<ReviewCommentResponse> addComment(@PathVariable Long pullRequestId,
            @Valid @RequestBody CreateStandaloneReviewCommentRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.addStandaloneComment(pullRequestId, request, userId(authentication)));
    }

    @PutMapping("/pull-requests/comments/{commentId}")
    public ReviewCommentResponse updateComment(@PathVariable Long commentId,
            @Valid @RequestBody UpdateReviewCommentRequest request, Authentication authentication) {
        return reviewService.updateComment(commentId, request, userId(authentication));
    }

    @DeleteMapping("/pull-requests/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable Long commentId, Authentication authentication) {
        reviewService.deleteComment(commentId, userId(authentication));
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) return null;
        return principal.getId();
    }
}
