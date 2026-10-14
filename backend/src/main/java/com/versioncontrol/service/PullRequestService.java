package com.versioncontrol.service;

import java.time.LocalDateTime;

import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import com.versioncontrol.dto.pullrequest.CreatePullRequestRequest;
import com.versioncontrol.dto.pullrequest.PullRequestPageResponse;
import com.versioncontrol.dto.pullrequest.PullRequestResponse;
import com.versioncontrol.dto.pullrequest.UpdatePullRequestRequest;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.PullRequestState;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.WorkflowTriggerType;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.UserRepository;

@Service
public class PullRequestService {
    private final PullRequestRepository pullRequests;
    private final RepositoryRepository repositories;
    private final BranchRepository branches;
    private final CommitGraphService commitGraph;
    private final RepositoryAccessService access;
    private final Validator validator;
    private final UserRepository users;
    private final RepositoryActivityService activity;
    private final ApplicationEventPublisher events;

    public PullRequestService(PullRequestRepository pullRequests, RepositoryRepository repositories,
            BranchRepository branches, CommitGraphService commitGraph, RepositoryAccessService access, Validator validator, UserRepository users,
            RepositoryActivityService activity, ApplicationEventPublisher events) {
        this.pullRequests = pullRequests;
        this.repositories = repositories;
        this.branches = branches;
        this.commitGraph = commitGraph;
        this.access = access;
        this.validator = validator;
        this.users = users;
        this.activity = activity;
        this.events = events;
    }

    @Transactional
    public PullRequestResponse create(Long repositoryId, CreatePullRequestRequest request, Long userId) {
        Repository repository = lockRepository(repositoryId);
        access.requireWrite(repository, userId);
        if (!branches.existsByRepository_IdAndName(repositoryId, "main"))
            throw new RepositoryConflictException("Repository must be initialized before creating pull requests");
        Branch source = branch(repositoryId, request.sourceBranch());
        Branch target = branch(repositoryId, request.targetBranch());
        validatePair(repositoryId, source, target);
        if (pullRequests.countOpenPair(repositoryId, source.getId(), target.getId(), null, PullRequestState.OPEN) > 0) {
            throw new RepositoryConflictException("An open pull request already exists for these branches");
        }
        String title = request.title().trim();
        if (title.isEmpty()) throw new InvalidRequestException("Title is required");
        PullRequest pr = new PullRequest(repository, pullRequests.findMaximumNumber(repositoryId) + 1,
                title, normalizeDescription(request.description()), users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found")), source, target,
                source.getHeadCommit(), target.getHeadCommit());
        try {
            PullRequest saved = pullRequests.saveAndFlush(pr);
            activity.record(repository, userId, com.versioncontrol.entity.RepositoryActivityEventType.PULL_REQUEST_OPENED,
                    "PULL_REQUEST", saved.getId(), "Opened pull request " + saved.getTitle(), "PULL_REQUEST_OPENED:" + saved.getId());
            events.publishEvent(new WorkflowTriggerEvent(repositoryId, WorkflowTriggerType.PULL_REQUEST, userId,
                    source.getName(), source.getHeadCommit().getCommitHash(), "pull-request-opened:" + saved.getId()));
            return PullRequestResponse.from(saved);
        } catch (DataIntegrityViolationException collision) {
            throw new RepositoryConflictException("Pull request conflicts with another update; retry the request");
        }
    }

    @Transactional(readOnly = true)
    public PullRequestPageResponse list(Long repositoryId, PullRequestState state, Long authorId, int page, int size, Long userId) {
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, userId);
        validatePage(page, size);
        Page<PullRequest> result = pullRequests.search(repositoryId, state, authorId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("number"))));
        return new PullRequestPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), result.getContent().stream().map(PullRequestResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public PullRequestResponse get(Long id, Long userId) {
        PullRequest pr = findDetailed(id);
        access.requireReadable(pr.getRepository(), userId);
        return PullRequestResponse.from(pr);
    }

    @Transactional
    public PullRequestResponse update(Long id, UpdatePullRequestRequest request, Long userId) {
        PullRequest pr = findDetailed(id);
        access.requireReadable(pr.getRepository(), userId);
        requireAuthorOrMaintainer(pr, userId);
        if (!validator.validate(request).isEmpty()) throw new InvalidRequestException("Pull request update contains invalid fields");
        if (!request.isTitlePresent() && !request.isDescriptionPresent()) throw new InvalidRequestException("At least one of title or description must be supplied");
        if (request.isTitlePresent()) {
            if (request.getTitle() == null || request.getTitle().isBlank()) throw new InvalidRequestException("Title is required");
            pr.setTitle(request.getTitle().trim());
        }
        if (request.isDescriptionPresent()) pr.setDescription(normalizeDescription(request.getDescription()));
        return PullRequestResponse.from(pullRequests.saveAndFlush(pr));
    }

    @Transactional
    public PullRequestResponse close(Long id, Long userId) {
        Long repositoryId = pullRequests.findRepositoryIdById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
        Repository repository = lockRepository(repositoryId);
        pullRequests.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
        PullRequest pr = findDetailed(id);
        access.requireReadable(repository, userId);
        requireAuthorOrMaintainer(pr, userId);
        if (pr.getState() == PullRequestState.MERGED) throw new RepositoryConflictException("Merged pull requests cannot be closed");
        if (pr.getState() == PullRequestState.CLOSED) return PullRequestResponse.from(pr);
        pr.setState(PullRequestState.CLOSED);
        pr.setClosedAt(LocalDateTime.now());
        PullRequest saved = pullRequests.saveAndFlush(pr);
        activity.record(repository, userId, com.versioncontrol.entity.RepositoryActivityEventType.PULL_REQUEST_CLOSED,
                "PULL_REQUEST", saved.getId(), "Closed pull request " + saved.getTitle(),
                "PULL_REQUEST_CLOSED:" + saved.getId() + ":" + saved.getClosedAt());
        return PullRequestResponse.from(saved);
    }

    @Transactional
    public PullRequestResponse reopen(Long id, Long userId) {
        Long repositoryId = pullRequests.findRepositoryIdById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
        Repository repository = lockRepository(repositoryId);
        pullRequests.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
        PullRequest pr = findDetailed(id);
        access.requireReadable(repository, userId);
        requireAuthorOrMaintainer(pr, userId);
        if (pr.getState() == PullRequestState.MERGED) throw new RepositoryConflictException("Merged pull requests cannot be reopened");
        if (pr.getState() == PullRequestState.OPEN) return PullRequestResponse.from(pr);
        validatePair(repository.getId(), pr.getSourceBranch(), pr.getTargetBranch());
        if (pullRequests.countOpenPair(repository.getId(), pr.getSourceBranch().getId(), pr.getTargetBranch().getId(), pr.getId(), PullRequestState.OPEN) > 0) {
            throw new RepositoryConflictException("Another open pull request already exists for these branches");
        }
        pr.setState(PullRequestState.OPEN);
        pr.setClosedAt(null);
        return PullRequestResponse.from(pullRequests.saveAndFlush(pr));
    }

    private Repository lockRepository(Long id) {
        return repositories.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
    }
    private Branch branch(Long repositoryId, String name) {
        return branches.findByRepository_IdAndName(repositoryId, name).orElseThrow(() -> new ResourceNotFoundException("Branch not found in this repository"));
    }
    private PullRequest findDetailed(Long id) {
        return pullRequests.findDetailedById(id).orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
    }
    private void requireAuthorOrMaintainer(PullRequest pr, Long userId) {
        if (userId == null || (!pr.getAuthor().getId().equals(userId) && !access.hasMaintain(pr.getRepository(), userId))) {
            throw new ResourceForbiddenException("Only the pull request author or a repository maintainer can perform this action");
        }
    }
    private void validatePair(Long repositoryId, Branch source, Branch target) {
        if (source.getId().equals(target.getId())) throw new InvalidRequestException("Source and target branches must differ");
        Long sourceHead = source.getHeadCommit().getId();
        Long targetHead = target.getHeadCommit().getId();
        if (sourceHead.equals(targetHead)) throw new RepositoryConflictException("Source and target branches have the same HEAD");
        if (commitGraph.load(repositoryId).isAncestor(sourceHead, targetHead))
            throw new RepositoryConflictException("Source branch has no commits that are not already in the target branch");
    }
    private String normalizeDescription(String description) {
        if (description == null) return null;
        String value = description.trim();
        return value.isEmpty() ? null : value;
    }
    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new InvalidRequestException("Page must be non-negative and size must be between 1 and 100");
    }
}
