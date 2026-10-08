package com.versioncontrol.dto.git;

public record CommitResponse(
        Long repositoryId,
        String branch,
        String commitHash,
        String parentCommitHash,
        String rootTreeHash,
        String message,
        String status
) { }
