package com.versioncontrol.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.versioncontrol.dto.diff.ComparisonResponse;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.service.FileDiffService.Result;
import com.versioncontrol.validation.BranchNameValidator;

@Service
public class BranchComparisonService {
    private static final int MAX_ANCESTRY = 50_000;
    private static final int MAX_PAGE_SIZE = 10;
    private static final int MAX_PAGE = 2_000;

    private final BranchRepository branches;
    private final CommitRepository commits;
    private final PullRequestRepository pullRequests;
    private final RepositoryAccessService access;
    private final FileDiffService fileDiffs;

    public BranchComparisonService(BranchRepository branches, CommitRepository commits,
            PullRequestRepository pullRequests, RepositoryAccessService access, FileDiffService fileDiffs) {
        this.branches = branches;
        this.commits = commits;
        this.pullRequests = pullRequests;
        this.access = access;
        this.fileDiffs = fileDiffs;
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
        if (commits.countByRepository_Id(repositoryId) > MAX_ANCESTRY) {
            throw new InvalidRequestException("Repository history exceeds the comparison limit");
        }
        Map<Long, CommitRepository.LineageRow> allCommits = new HashMap<>();
        for (CommitRepository.LineageRow row : commits.findAllLineageByRepository_Id(repositoryId)) {
            allCommits.put(row.getId(), row);
        }
        List<CommitRepository.LineageRow> baseHistory = lineage(repositoryId, base.getId(), allCommits);
        List<CommitRepository.LineageRow> headHistory = lineage(repositoryId, head.getId(), allCommits);
        Map<Long, Integer> basePositions = new HashMap<>();
        for (int i = 0; i < baseHistory.size(); i++) basePositions.put(baseHistory.get(i).getId(), i);
        String mergeBaseHash = null;
        int ahead = headHistory.size();
        int behind = baseHistory.size();
        for (int i = 0; i < headHistory.size(); i++) {
            Integer baseIndex = basePositions.get(headHistory.get(i).getId());
            if (baseIndex != null) {
                mergeBaseHash = headHistory.get(i).getCommitHash();
                ahead = i;
                behind = baseIndex;
                break;
            }
        }
        Result diff = fileDiffs.compare(base.getRootTree(), head.getRootTree(), page, size);
        return new ComparisonResponse(repositoryId, baseName, headName,
                base.getCommitHash(), head.getCommitHash(), mergeBaseHash, ahead, behind,
                diff.filesChanged(), diff.filesAdded(), diff.filesModified(), diff.filesDeleted(),
                diff.additions(), diff.deletions(), diff.filesTruncated(),
                page, size, diff.changes());
    }

    private List<CommitRepository.LineageRow> lineage(Long repositoryId, Long headId,
            Map<Long, CommitRepository.LineageRow> allCommits) {
        List<CommitRepository.LineageRow> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Long current = headId;
        while (current != null) {
            if (!visited.add(current)) throw new RepositoryConflictException("Commit history contains a cycle");
            if (result.size() >= MAX_ANCESTRY) throw new InvalidRequestException("Commit history exceeds the comparison limit");
            CommitRepository.LineageRow row = allCommits.get(current);
            if (row == null) throw new RepositoryConflictException("Commit history contains a missing or cross-repository parent");
            if (row.getParentRepositoryId() != null && !repositoryId.equals(row.getParentRepositoryId())) {
                throw new RepositoryConflictException("Commit history contains a cross-repository parent");
            }
            result.add(row);
            current = row.getParentId();
        }
        return result;
    }

    private void validatePaging(int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("Diff page must be between 0 and 2000 and size between 1 and 10");
        }
    }
}
