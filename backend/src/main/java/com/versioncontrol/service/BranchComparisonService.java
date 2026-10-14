package com.versioncontrol.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.versioncontrol.dto.diff.ComparisonResponse;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.service.FileDiffService.Result;
import com.versioncontrol.validation.BranchNameValidator;

@Service
public class BranchComparisonService {
    private static final int MAX_PAGE_SIZE = 10;
    private static final int MAX_PAGE = 2_000;

    private final BranchRepository branches;
    private final PullRequestRepository pullRequests;
    private final RepositoryAccessService access;
    private final FileDiffService fileDiffs;
    private final CommitGraphService graphService;

    public BranchComparisonService(BranchRepository branches,
            PullRequestRepository pullRequests, RepositoryAccessService access, FileDiffService fileDiffs,
            CommitGraphService graphService) {
        this.branches = branches;
        this.pullRequests = pullRequests;
        this.access = access;
        this.fileDiffs = fileDiffs;
        this.graphService = graphService;
    }

    @Transactional(readOnly = true)
    public ComparisonResponse compareBranches(Long repositoryId, String baseName, String headName,
            int page, int size, Long userId) {
        validatePaging(page, size);
        if (!BranchNameValidator.isValidName(baseName) || !BranchNameValidator.isValidName(headName)) {
            throw new InvalidRequestException("Branch name is invalid");
        }
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, userId);
        Branch base = branches.findByRepository_IdAndName(repositoryId, baseName)
                .orElseThrow(() -> new ResourceNotFoundException("Base branch not found"));
        Branch head = branches.findByRepository_IdAndName(repositoryId, headName)
                .orElseThrow(() -> new ResourceNotFoundException("Head branch not found"));
        return compare(repositoryId, base.getName(), head.getName(), base.getHeadCommit(), head.getHeadCommit(), page, size);
    }

    @Transactional(readOnly = true)
    public ComparisonResponse comparePullRequest(Long pullRequestId, int page, int size, Long userId) {
        validatePaging(page, size);
        PullRequest pr = pullRequests.findDetailedById(pullRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
        access.requireReadable(pr.getRepository(), userId);
        Branch base = pr.getTargetBranch();
        Branch head = pr.getSourceBranch();
        return compare(pr.getRepository().getId(), base.getName(), head.getName(),
                base.getHeadCommit(), head.getHeadCommit(), page, size);
    }

    private ComparisonResponse compare(Long repositoryId, String baseName, String headName,
            Commit base, Commit head, int page, int size) {
        CommitGraphService.MergeBase mergeBase = graphService.load(repositoryId).mergeBase(head.getId(), base.getId());
        Result diff = fileDiffs.compare(base.getRootTree(), head.getRootTree(), page, size);
        return new ComparisonResponse(repositoryId, baseName, headName,
                base.getCommitHash(), head.getCommitHash(), mergeBase == null ? null : mergeBase.commitHash(),
                mergeBase == null ? 0 : mergeBase.aheadBy(), mergeBase == null ? 0 : mergeBase.behindBy(),
                diff.filesChanged(), diff.filesAdded(), diff.filesModified(), diff.filesDeleted(),
                diff.additions(), diff.deletions(), diff.filesTruncated(),
                page, size, diff.changes());
    }

    private void validatePaging(int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("Diff page must be between 0 and 2000 and size between 1 and 10");
        }
    }
}
