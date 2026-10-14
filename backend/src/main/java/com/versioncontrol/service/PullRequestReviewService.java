package com.versioncontrol.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import com.versioncontrol.dto.review.CreateStandaloneReviewCommentRequest;
import com.versioncontrol.dto.review.InlineReviewCommentRequest;
import com.versioncontrol.dto.review.PullRequestReviewResponse;
import com.versioncontrol.dto.review.ReviewCommentPageResponse;
import com.versioncontrol.dto.review.ReviewCommentResponse;
import com.versioncontrol.dto.review.ReviewPageResponse;
import com.versioncontrol.dto.review.ReviewStatusResponse;
import com.versioncontrol.dto.review.ReviewStatusResponse.EffectiveReview;
import com.versioncontrol.dto.review.SubmitPullRequestReviewRequest;
import com.versioncontrol.dto.review.UpdateReviewCommentRequest;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.PullRequestReview;
import com.versioncontrol.entity.PullRequestReviewComment;
import com.versioncontrol.entity.PullRequestReviewState;
import com.versioncontrol.entity.NotificationEventType;
import com.versioncontrol.entity.ReviewCommentSide;
import com.versioncontrol.entity.User;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.repository.PullRequestReviewCommentRepository;
import com.versioncontrol.repository.PullRequestReviewRepository;
import com.versioncontrol.repository.UserRepository;

@Service
public class PullRequestReviewService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 2_000;
    private static final int MAX_STATUS_REVIEWS = 5_000;

    private final PullRequestRepository pullRequests;
    private final PullRequestReviewRepository reviews;
    private final PullRequestReviewCommentRepository comments;
    private final BranchRepository branches;
    private final RepositoryAccessService access;
    private final FileDiffService fileDiffs;
    private final EntityManager entityManager;
    private final UserRepository users;
    private final NotificationService notifications;

    public PullRequestReviewService(PullRequestRepository pullRequests, PullRequestReviewRepository reviews,
            PullRequestReviewCommentRepository comments, BranchRepository branches,
            RepositoryAccessService access, FileDiffService fileDiffs, EntityManager entityManager, UserRepository users,
            NotificationService notifications) {
        this.pullRequests = pullRequests;
        this.reviews = reviews;
        this.comments = comments;
        this.branches = branches;
        this.access = access;
        this.fileDiffs = fileDiffs;
        this.entityManager = entityManager;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public PullRequestReviewResponse submit(Long pullRequestId, SubmitPullRequestReviewRequest request, Long userId) {
        PullRequest pr = lockPullRequest(pullRequestId);
        access.requireReadable(pr.getRepository(), userId);
        requireReviewer(pr, request.state(), userId);
        requireOpen(pr);
        if (request.comments() != null && request.comments().size() > 20) throw new InvalidRequestException("A review may contain at most 20 inline comments");
        String body = normalize(request.body());
        int commentCount = request.comments() == null ? 0 : request.comments().size();
        if (request.state() != PullRequestReviewState.APPROVED && body == null && commentCount == 0) {
            throw new InvalidRequestException("A change request or comment review requires review text or inline comments");
        }
        LockedHeads heads = lockCurrentHeads(pr);
        for (InlineReviewCommentRequest comment : safeComments(request.comments())) {
            fileDiffs.validateInlineComment(heads.target().getHeadCommit().getRootTree(),
                    heads.source().getHeadCommit().getRootTree(), comment.path(), comment.lineNumber(), comment.side());
        }
        User reviewer = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Reviewer not found"));
        PullRequestReview review = reviews.saveAndFlush(new PullRequestReview(pr, reviewer,
                request.state(), body, heads.source().getHeadCommit().getId(), heads.source().getHeadCommit().getCommitHash()));
        for (InlineReviewCommentRequest comment : safeComments(request.comments())) {
            comments.save(new PullRequestReviewComment(review, reviewer, comment.path(),
                    comment.lineNumber(), comment.side(), comment.body().trim()));
        }
        comments.flush();
        List<PullRequestReviewComment> savedComments = comments.findByReview_IdInOrderByIdAsc(List.of(review.getId()));
        NotificationEventType notificationType = request.state() == PullRequestReviewState.COMMENTED
                ? NotificationEventType.PULL_REQUEST_REVIEW_COMMENTED : NotificationEventType.PULL_REQUEST_REVIEWED;
        notifications.notifyRecipients(List.of(pr.getAuthor().getId()), userId, pr.getRepository(), notificationType,
                request.state() == PullRequestReviewState.COMMENTED ? "New pull request comment" : "Pull request review submitted",
                "A review was submitted for pull request " + pr.getTitle(), "PULL_REQUEST", pr.getId(),
                "PR_REVIEW:" + review.getId());
        return reviewResponse(review, savedComments);
    }

    @Transactional(readOnly = true)
    public ReviewPageResponse list(Long pullRequestId, int page, int size, Long userId) {
        PullRequest pr = findPullRequest(pullRequestId);
        access.requireReadable(pr.getRepository(), userId);
        validatePage(page, size);
        Page<PullRequestReview> result = reviews.findByPullRequest_Id(pullRequestId,
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
        List<Long> reviewIds = result.getContent().stream().map(PullRequestReview::getId).toList();
        Map<Long, List<ReviewCommentResponse>> commentsByReview = new HashMap<>();
        if (!reviewIds.isEmpty()) {
            for (PullRequestReviewComment comment : comments.findByReview_IdInOrderByIdAsc(reviewIds)) {
                commentsByReview.computeIfAbsent(comment.getReview().getId(), ignored -> new ArrayList<>())
                        .add(ReviewCommentResponse.from(comment));
            }
        }
        return new ReviewPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), result.getContent().stream()
                        .map(review -> PullRequestReviewResponse.from(review,
                                commentsByReview.getOrDefault(review.getId(), List.of()))).toList());
    }

    @Transactional(readOnly = true)
    public ReviewStatusResponse status(Long pullRequestId, Long userId) {
        PullRequest pr = findPullRequest(pullRequestId);
        access.requireReadable(pr.getRepository(), userId);
        long total = reviews.countByPullRequest_Id(pullRequestId);
        if (total > MAX_STATUS_REVIEWS) throw new InvalidRequestException("Review status exceeds the supported history limit");
        List<PullRequestReview> all = reviews.findByPullRequest_IdOrderByCreatedAtAscIdAsc(pullRequestId);
        Map<Long, PullRequestReview> effective = new HashMap<>();
        PullRequestReview latestEffective = null;
        for (PullRequestReview review : all) {
            if (review.getState() != PullRequestReviewState.COMMENTED) {
                effective.put(review.getReviewer().getId(), review);
                latestEffective = review;
            }
        }
        Long currentHeadId = pr.getSourceBranch().getHeadCommit().getId();
        String currentHeadHash = pr.getSourceBranch().getHeadCommit().getCommitHash();
        long approvals = 0, changeRequests = 0, staleApprovals = 0;
        List<EffectiveReview> effectiveReviews = new ArrayList<>();
        for (PullRequestReview review : effective.values()) {
            boolean stale = !review.getReviewedHeadCommitId().equals(currentHeadId);
            boolean selfReview = review.getReviewer().getId().equals(pr.getAuthor().getId());
            if (!stale && !selfReview && review.getState() == PullRequestReviewState.APPROVED) approvals++;
            if (!stale && !selfReview && review.getState() == PullRequestReviewState.CHANGES_REQUESTED) changeRequests++;
            if (stale && !selfReview && review.getState() == PullRequestReviewState.APPROVED) staleApprovals++;
            effectiveReviews.add(new EffectiveReview(review.getReviewer().getId(), review.getReviewer().getUsername(),
                    review.getState(), review.getReviewedHeadCommitHash(), stale, selfReview));
        }
        effectiveReviews.sort(Comparator.comparing(EffectiveReview::reviewerId));
        return new ReviewStatusResponse(pullRequestId, total, List.copyOf(effectiveReviews), approvals,
                changeRequests, staleApprovals,
                latestEffective == null ? null : latestEffective.getReviewedHeadCommitHash(), currentHeadHash,
                false, null);
    }

    /** Initial merge policy: at least one current non-author approval and no current effective change request. */
    @Transactional(readOnly = true)
    public MergeApprovalDecision evaluateMergeApproval(PullRequest pr, Long currentSourceHeadId) {
        long total = reviews.countByPullRequest_Id(pr.getId());
        if (total > MAX_STATUS_REVIEWS) throw new InvalidRequestException("Review history exceeds the supported merge limit");
        Map<Long, PullRequestReview> effective = new HashMap<>();
        for (PullRequestReview review : reviews.findByPullRequest_IdOrderByCreatedAtAscIdAsc(pr.getId())) {
            if (review.getState() != PullRequestReviewState.COMMENTED) effective.put(review.getReviewer().getId(), review);
        }
        long approvals = 0;
        long changesRequested = 0;
        for (PullRequestReview review : effective.values()) {
            if (!review.getReviewedHeadCommitId().equals(currentSourceHeadId)
                    || review.getReviewer().getId().equals(pr.getAuthor().getId())) continue;
            if (review.getState() == PullRequestReviewState.APPROVED) approvals++;
            if (review.getState() == PullRequestReviewState.CHANGES_REQUESTED) changesRequested++;
        }
        return new MergeApprovalDecision(approvals, changesRequested);
    }

    public record MergeApprovalDecision(long currentApprovals, long currentChangeRequests) {
        public boolean allowsMerge() { return currentApprovals > 0 && currentChangeRequests == 0; }
    }

    @Transactional(readOnly = true)
    public ReviewCommentPageResponse listComments(Long pullRequestId, int page, int size, Long userId) {
        PullRequest pr = findPullRequest(pullRequestId);
        access.requireReadable(pr.getRepository(), userId);
        validatePage(page, size);
        Page<PullRequestReviewComment> result = comments.findByPullRequestId(pullRequestId,
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
        return new ReviewCommentPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), result.getContent().stream().map(ReviewCommentResponse::from).toList());
    }

    @Transactional
    public ReviewCommentResponse addStandaloneComment(Long pullRequestId, CreateStandaloneReviewCommentRequest request, Long userId) {
        InlineReviewCommentRequest inline = new InlineReviewCommentRequest(request.path(), request.lineNumber(), request.side(), request.body());
        SubmitPullRequestReviewRequest submission = new SubmitPullRequestReviewRequest(
                PullRequestReviewState.COMMENTED, null, List.of(inline));
        PullRequestReviewResponse response = submit(pullRequestId, submission, userId);
        return response.comments().getFirst();
    }

    @Transactional
    public ReviewCommentResponse updateComment(Long commentId, UpdateReviewCommentRequest request, Long userId) {
        PullRequestReviewComment comment = findComment(commentId);
        PullRequest pr = comment.getReview().getPullRequest();
        access.requireReadable(pr.getRepository(), userId);
        requireCommentOwner(comment, pr, userId);
        comment.setBody(request.body().trim());
        return ReviewCommentResponse.from(comments.saveAndFlush(comment));
    }

    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        PullRequestReviewComment comment = findComment(commentId);
        PullRequest pr = comment.getReview().getPullRequest();
        access.requireReadable(pr.getRepository(), userId);
        requireCommentOwner(comment, pr, userId);
        comments.delete(comment);
    }

    private LockedHeads lockCurrentHeads(PullRequest pr) {
        Branch sourceRef = pr.getSourceBranch();
        Branch targetRef = pr.getTargetBranch();
        Branch first = sourceRef.getId() < targetRef.getId() ? sourceRef : targetRef;
        Branch second = first == sourceRef ? targetRef : sourceRef;
        Branch firstLocked = branches.findByRepositoryAndNameForUpdate(pr.getRepository().getId(), first.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Pull request branch not found"));
        Branch secondLocked = branches.findByRepositoryAndNameForUpdate(pr.getRepository().getId(), second.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Pull request branch not found"));
        entityManager.refresh(firstLocked, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(secondLocked, LockModeType.PESSIMISTIC_WRITE);
        return new LockedHeads(firstLocked.getId().equals(sourceRef.getId()) ? firstLocked : secondLocked,
                firstLocked.getId().equals(targetRef.getId()) ? firstLocked : secondLocked);
    }

    private PullRequestReviewResponse reviewResponse(PullRequestReview review, List<PullRequestReviewComment> rows) {
        return PullRequestReviewResponse.from(review, rows.stream().map(ReviewCommentResponse::from).toList());
    }

    private PullRequest lockPullRequest(Long id) {
        pullRequests.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
        return findPullRequest(id);
    }

    private PullRequest findPullRequest(Long id) {
        return pullRequests.findDetailedById(id).orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
    }

    private PullRequestReviewComment findComment(Long id) {
        return comments.findDetailedById(id).orElseThrow(() -> new ResourceNotFoundException("Review comment not found"));
    }

    private void requireReviewer(PullRequest pr, PullRequestReviewState state, Long userId) {
        if (userId == null) throw new ResourceForbiddenException("Authentication is required to submit a review");
        boolean author = pr.getAuthor().getId().equals(userId);
        access.requireWrite(pr.getRepository(), userId);
        if (state == PullRequestReviewState.APPROVED && author) {
            throw new ResourceForbiddenException("Pull request authors cannot approve their own pull request");
        }
    }

    private void requireCommentOwner(PullRequestReviewComment comment, PullRequest pr, Long userId) {
        if (userId == null || (!comment.getAuthor().getId().equals(userId) && !access.hasMaintain(pr.getRepository(), userId))) {
            throw new ResourceForbiddenException("Only the comment author or a repository maintainer may modify this comment");
        }
    }

    private void requireOpen(PullRequest pr) {
        if (pr.getState() != com.versioncontrol.entity.PullRequestState.OPEN) {
            throw new RepositoryConflictException("Closed or merged pull requests do not accept new reviews");
        }
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private List<InlineReviewCommentRequest> safeComments(List<InlineReviewCommentRequest> values) { return values == null ? List.of() : values; }

    private void validatePage(int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("Page must be between 0 and 2000 and size between 1 and 50");
        }
    }

    private record LockedHeads(Branch source, Branch target) { }
}
