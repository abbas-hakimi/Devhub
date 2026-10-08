package com.versioncontrol.dto.git;

public record GitInitializationResponse(
        Long repositoryId,
        String branchName,
        String commitHash,
        String rootTreeHash,
        String status,
        String message
) { }
