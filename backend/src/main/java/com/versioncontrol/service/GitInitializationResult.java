package com.versioncontrol.service;

import com.versioncontrol.dto.git.GitInitializationResponse;
import com.versioncontrol.entity.Branch;

public record GitInitializationResult(GitInitializationResponse response, boolean initializedNow) {
    public static GitInitializationResult from(Branch branch, boolean initializedNow) {
        var head = branch.getHeadCommit();
        GitInitializationResponse response = new GitInitializationResponse(
                branch.getRepository().getId(), branch.getName(), head.getCommitHash(),
                head.getRootTree().getTreeHash(), initializedNow ? "INITIALIZED" : "ALREADY_INITIALIZED",
                initializedNow ? "Repository initialized" : "Repository is already initialized");
        return new GitInitializationResult(response, initializedNow);
    }
}
