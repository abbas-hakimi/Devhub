package com.versioncontrol.dto.git;

import java.time.LocalDateTime;

import com.versioncontrol.entity.Branch;

public record BranchResponse(
        Long id,
        Long repositoryId,
        String name,
        CommitSummary headCommit,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static BranchResponse from(Branch branch) {
        var head = branch.getHeadCommit();
        var parent = head.getParentCommit();
        return new BranchResponse(branch.getId(), branch.getRepository().getId(), branch.getName(),
                new CommitSummary(head.getCommitHash(), head.getMessage(), head.getAuthor().getId(),
                        head.getRootTree().getTreeHash(), parent == null ? null : parent.getCommitHash(), head.getCreatedAt()),
                branch.getCreatedAt(), branch.getUpdatedAt());
    }

    public record CommitSummary(String commitHash, String message, Long authorId, String rootTreeHash,
            String parentCommitHash, LocalDateTime createdAt) { }
}
