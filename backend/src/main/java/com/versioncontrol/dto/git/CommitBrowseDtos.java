package com.versioncontrol.dto.git;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class CommitBrowseDtos {
    private CommitBrowseDtos() { }

    public record Author(Long id, String username) { }

    public record CommitMetadata(String commitHash, String parentCommitHash, String message,
            Author author, Long repositoryId, String rootTreeHash, LocalDateTime createdAt) { }

    public record CommitHistoryItem(String commitHash, String parentCommitHash, String message,
            Author author, String rootTreeHash, LocalDateTime createdAt) { }

    public record CommitHistory(int page, int size, boolean hasMore, List<CommitHistoryItem> commits) { }

    public record TreeListing(String commitHash, String treeHash, String path, List<TreeEntryItem> entries) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TreeEntryItem(String name, String type, String blobHash, Long size, String treeHash) { }

    public record BlobMetadata(String blobHash, long size) { }
}
