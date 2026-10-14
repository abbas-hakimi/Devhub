package com.versioncontrol.service;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.context.ApplicationEventPublisher;

import com.versioncontrol.dto.pullrequest.MergePullRequestRequest;
import com.versioncontrol.dto.pullrequest.MergePullRequestResponse;
import com.versioncontrol.dto.pullrequest.MergeStrategy;
import com.versioncontrol.dto.pullrequest.MergeabilityResponse;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.PullRequestState;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.User;
import com.versioncontrol.entity.WorkflowTriggerType;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.UserRepository;
import com.versioncontrol.versioncontrol.hash.CommitCanonicalizer;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;

@Service
public class PullRequestMergeService {
    private final PullRequestRepository pullRequests;
    private final RepositoryRepository repositories;
    private final BranchRepository branches;
    private final CommitRepository commits;
    private final RepositoryAccessService access;
    private final CommitGraphService graphService;
    private final MergeTreeService trees;
    private final PullRequestReviewService reviewService;
    private final UserRepository users;
    private final ObjectStorage storage;
    private final EntityManager entityManager;
    private final RepositoryActivityService activity;
    private final ApplicationEventPublisher events;

    public PullRequestMergeService(PullRequestRepository pullRequests, RepositoryRepository repositories,
            BranchRepository branches, CommitRepository commits, RepositoryAccessService access,
            CommitGraphService graphService, MergeTreeService trees, PullRequestReviewService reviewService,
            UserRepository users, ObjectStorage storage, EntityManager entityManager, RepositoryActivityService activity,
            ApplicationEventPublisher events) {
        this.pullRequests = pullRequests; this.repositories = repositories; this.branches = branches;
        this.commits = commits; this.access = access; this.graphService = graphService; this.trees = trees;
        this.reviewService = reviewService; this.users = users; this.storage = storage; this.entityManager = entityManager;
        this.activity = activity;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public MergeabilityResponse mergeability(Long id, Long userId) {
        PullRequest pr = findDetailed(id);
        access.requireReadable(pr.getRepository(), userId);
        Branch source = branches.findWithDetailsById(pr.getSourceBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Pull request source branch not found"));
        Branch target = branches.findWithDetailsById(pr.getTargetBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Pull request target branch not found"));
        Commit sourceHead = source.getHeadCommit(), targetHead = target.getHeadCommit();
        CommitGraphService.Graph graph = graphService.load(pr.getRepository().getId());
        CommitGraphService.MergeBase base = graph.mergeBase(sourceHead.getId(), targetHead.getId());
        PullRequestReviewService.MergeApprovalDecision approval = reviewService.evaluateMergeApproval(pr, sourceHead.getId());
        List<String> conflicts = List.of();
        String reason = mergeBlockReason(pr, sourceHead, targetHead, base, approval, graph);
        boolean fastForward = base != null && graph.isAncestor(targetHead.getId(), sourceHead.getId());
        if (base != null && !fastForward && !graph.isAncestor(sourceHead.getId(), targetHead.getId())) {
            Commit baseCommit = findCommit(base.commitId());
            conflicts = trees.plan(baseCommit.getRootTree(), sourceHead.getRootTree(), targetHead.getRootTree()).conflictPaths();
            if (reason == null && !conflicts.isEmpty()) reason = "Merge has file conflicts";
        }
        boolean mergeable = reason == null;
        return new MergeabilityResponse(id, mergeable, sourceHead.getCommitHash(), targetHead.getCommitHash(),
                base == null ? null : base.commitHash(), base == null ? 0 : base.aheadBy(),
                base == null ? 0 : base.behindBy(), conflicts, fastForward,
                approval.currentApprovals(), approval.currentChangeRequests(), reason);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MergePullRequestResponse merge(Long id, MergePullRequestRequest request, Long userId) {
        // Global lock order: repository, pull request, then branch rows in ascending ID order.
        Long repositoryId = pullRequests.findRepositoryIdById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
        Repository repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        pullRequests.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
        PullRequest pr = findDetailed(id);
        access.requireReadable(repository, userId);
        access.requireMaintain(repository, userId);
        if (pr.getState() != PullRequestState.OPEN) throw new RepositoryConflictException("Only open pull requests can be merged");

        Long firstId = Math.min(pr.getSourceBranchId(), pr.getTargetBranchId());
        Long secondId = Math.max(pr.getSourceBranchId(), pr.getTargetBranchId());
        Branch first = lockBranch(repositoryId, firstId);
        Branch second = lockBranch(repositoryId, secondId);
        entityManager.refresh(first, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(second, LockModeType.PESSIMISTIC_WRITE);
        Branch source = first.getId().equals(pr.getSourceBranchId()) ? first : second;
        Branch target = first.getId().equals(pr.getTargetBranchId()) ? first : second;
        Commit sourceHead = source.getHeadCommit(), targetHead = target.getHeadCommit();
        if (!sourceHead.getCommitHash().equals(request.expectedSourceHead())
                || !targetHead.getCommitHash().equals(request.expectedTargetHead())) {
            throw new RepositoryConflictException("Source or target HEAD changed; refresh the pull request and retry");
        }
        if (!source.getRepository().getId().equals(target.getRepository().getId())
                || !source.getRepository().getId().equals(repositoryId)) {
            throw new ResourceNotFoundException("Pull request branch not found");
        }
        CommitGraphService.Graph graph = graphService.load(repositoryId);
        CommitGraphService.MergeBase base = graph.mergeBase(sourceHead.getId(), targetHead.getId());
        if (base == null) throw new RepositoryConflictException("Branches have unrelated commit histories");
        if (base.ambiguous()) throw new RepositoryConflictException("History has multiple best merge bases and requires manual resolution");
        if (graph.isAncestor(sourceHead.getId(), targetHead.getId())) {
            throw new RepositoryConflictException("Source branch is already contained in the target branch");
        }
        if (request.strategy() == MergeStrategy.FAST_FORWARD) {
            if (!graph.isAncestor(targetHead.getId(), sourceHead.getId())) {
                throw new RepositoryConflictException("Fast-forward is unavailable because the branches have diverged");
            }
            requireApproved(pr, sourceHead);
            target.setHeadCommit(sourceHead);
            branches.saveAndFlush(target);
            events.publishEvent(new WorkflowTriggerEvent(repository.getId(), WorkflowTriggerType.PUSH, userId,
                    target.getName(), sourceHead.getCommitHash(), "push:" + repository.getId() + ":" + target.getId() + ":" + sourceHead.getCommitHash()));
            LocalDateTime mergedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            User merger = findUser(userId);
            pr.markMerged(merger, mergedAt);
            pullRequests.saveAndFlush(pr);
            activity.record(repository, userId, com.versioncontrol.entity.RepositoryActivityEventType.PULL_REQUEST_MERGED,
                    "PULL_REQUEST", pr.getId(), "Merged pull request " + pr.getTitle(), "PULL_REQUEST_MERGED:" + pr.getId());
            return new MergePullRequestResponse(id, PullRequestState.MERGED, request.strategy(),
                    sourceHead.getCommitHash(), sourceHead.getCommitHash(), base.commitHash(), null, mergedAt,
                    List.of(), "Pull request merged by fast-forward");
        }

        requireApproved(pr, sourceHead);
        Commit baseCommit = findCommit(base.commitId());
        MergeTreeService.Plan plan = trees.plan(baseCommit.getRootTree(), sourceHead.getRootTree(), targetHead.getRootTree());
        if (!plan.conflictPaths().isEmpty()) {
            return new MergePullRequestResponse(id, pr.getState(), request.strategy(), sourceHead.getCommitHash(),
                    targetHead.getCommitHash(), base.commitHash(), null, null, plan.conflictPaths(), "Merge conflicts must be resolved first");
        }

        var rootTree = trees.persist(plan);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant latestParent = sourceHead.getCreatedAt().toInstant(ZoneOffset.UTC)
                .isAfter(targetHead.getCreatedAt().toInstant(ZoneOffset.UTC))
                        ? sourceHead.getCreatedAt().toInstant(ZoneOffset.UTC) : targetHead.getCreatedAt().toInstant(ZoneOffset.UTC);
        if (!createdAt.isAfter(latestParent)) createdAt = latestParent.plus(1, ChronoUnit.MICROS);
        String hash = CommitCanonicalizer.hashMerge(repositoryId, targetHead.getCommitHash(), sourceHead.getCommitHash(),
                rootTree.getTreeHash(), userId, createdAt, request.commitMessage());
        byte[] object = CommitCanonicalizer.canonicalizeMerge(repositoryId, targetHead.getCommitHash(),
                sourceHead.getCommitHash(), rootTree.getTreeHash(), userId, createdAt, request.commitMessage());
        // Object writes are immutable and outside JDBC rollback; a failed DB transaction can leave only unreachable objects.
        storage.write(hash, new ByteArrayInputStream(object), object.length);
        User merger = findUser(userId);
        LocalDateTime persistedAt = LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        Commit mergeCommit = commits.saveAndFlush(new Commit(repository, hash, request.commitMessage(), merger,
                rootTree, targetHead, sourceHead, persistedAt));
        target.setHeadCommit(mergeCommit);
        branches.saveAndFlush(target);
        events.publishEvent(new WorkflowTriggerEvent(repository.getId(), WorkflowTriggerType.PUSH, userId,
                target.getName(), mergeCommit.getCommitHash(), "push:" + repository.getId() + ":" + target.getId() + ":" + mergeCommit.getCommitHash()));
        pr.markMerged(merger, persistedAt);
        pullRequests.saveAndFlush(pr);
        activity.record(repository, userId, com.versioncontrol.entity.RepositoryActivityEventType.COMMIT_CREATED,
                "COMMIT", mergeCommit.getId(), "Created merge commit " + mergeCommit.getCommitHash().substring(0, 12), "COMMIT_CREATED:" + mergeCommit.getId());
        activity.record(repository, userId, com.versioncontrol.entity.RepositoryActivityEventType.PULL_REQUEST_MERGED,
                "PULL_REQUEST", pr.getId(), "Merged pull request " + pr.getTitle(), "PULL_REQUEST_MERGED:" + pr.getId());
        return new MergePullRequestResponse(id, PullRequestState.MERGED, request.strategy(),
                sourceHead.getCommitHash(), mergeCommit.getCommitHash(), base.commitHash(), mergeCommit.getCommitHash(),
                persistedAt, List.of(), "Pull request merged with a two-parent merge commit");
    }

    private void requireApproved(PullRequest pr, Commit sourceHead) {
        PullRequestReviewService.MergeApprovalDecision approval = reviewService.evaluateMergeApproval(pr, sourceHead.getId());
        if (approval.currentApprovals() == 0) throw new RepositoryConflictException("A current non-author approval is required to merge");
        if (approval.currentChangeRequests() > 0) throw new RepositoryConflictException("Current change requests must be resolved before merging");
    }

    private String mergeBlockReason(PullRequest pr, Commit source, Commit target,
            CommitGraphService.MergeBase base, PullRequestReviewService.MergeApprovalDecision approval,
            CommitGraphService.Graph graph) {
        if (pr.getState() != PullRequestState.OPEN) return "Pull request is not open";
        if (base == null) return "Branches have unrelated commit histories";
        if (base.ambiguous()) return "History has multiple best merge bases and requires manual resolution";
        if (graph.isAncestor(source.getId(), target.getId()))
            return "Source branch is already contained in the target branch";
        if (approval.currentChangeRequests() > 0) return "Current changes requested reviews block merging";
        if (approval.currentApprovals() == 0) return "A current non-author approval is required";
        return null;
    }

    private Branch lockBranch(Long repositoryId, Long branchId) {
        return branches.findByRepositoryAndIdForUpdate(repositoryId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request branch not found"));
    }
    private PullRequest findDetailed(Long id) {
        return pullRequests.findDetailedById(id).orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
    }
    private Commit findCommit(Long id) {
        return commits.findDetailedById(id).orElseThrow(() -> new RepositoryConflictException("Merge base commit is missing"));
    }
    private User findUser(Long id) { return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found")); }
}
