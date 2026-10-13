package com.versioncontrol.dto.pullrequest;

import java.time.LocalDateTime;

import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.PullRequestState;

public record PullRequestResponse(Long id, Long repositoryId, Long number, String title, String description,
        PullRequestState state, AuthorSummary author, BranchSummary sourceBranch, BranchSummary targetBranch,
        String sourceHeadHashAtCreation, String targetHeadHashAtCreation, LocalDateTime createdAt,
        LocalDateTime updatedAt, LocalDateTime closedAt, LocalDateTime mergedAt, AuthorSummary mergedBy) {
    public record AuthorSummary(Long id, String username) { }
    public record BranchSummary(Long id, String name, String currentHeadHash) { }

    public static PullRequestResponse from(PullRequest p) {
        return new PullRequestResponse(p.getId(), p.getRepository().getId(), p.getNumber(), p.getTitle(), p.getDescription(),
                p.getState(), new AuthorSummary(p.getAuthor().getId(), p.getAuthor().getUsername()),
                new BranchSummary(p.getSourceBranch().getId(), p.getSourceBranch().getName(), p.getSourceBranch().getHeadCommit().getCommitHash()),
                new BranchSummary(p.getTargetBranch().getId(), p.getTargetBranch().getName(), p.getTargetBranch().getHeadCommit().getCommitHash()),
                p.getSourceHeadCommitAtCreation().getCommitHash(), p.getTargetHeadCommitAtCreation().getCommitHash(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getClosedAt(), p.getMergedAt(),
                p.getMergedBy() == null ? null : new AuthorSummary(p.getMergedBy().getId(), p.getMergedBy().getUsername()));
    }
}
