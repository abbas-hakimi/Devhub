package com.versioncontrol.dto.diff;

import java.util.List;

public record ComparisonResponse(Long repositoryId, String baseBranch, String headBranch,
        String baseCommitHash, String headCommitHash, String mergeBaseHash,
        int aheadBy, int behindBy, int filesChanged, int filesAdded, int filesModified,
        int filesDeleted, int additions, int deletions, boolean filesTruncated, int page, int size,
        List<FileChangeResponse> changes) { }
