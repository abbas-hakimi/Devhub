package com.versioncontrol.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.versioncontrol.dto.git.CommitBrowseDtos.Author;
import com.versioncontrol.dto.git.CommitBrowseDtos.BlobMetadata;
import com.versioncontrol.dto.git.CommitBrowseDtos.CommitHistory;
import com.versioncontrol.dto.git.CommitBrowseDtos.CommitHistoryItem;
import com.versioncontrol.dto.git.CommitBrowseDtos.CommitMetadata;
import com.versioncontrol.dto.git.CommitBrowseDtos.TreeEntryItem;
import com.versioncontrol.dto.git.CommitBrowseDtos.TreeListing;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.Tree;
import com.versioncontrol.entity.TreeEntry;
import com.versioncontrol.entity.TreeEntryType;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.TreeEntryRepository;
import com.versioncontrol.validation.TreeEntryNameValidator;
import com.versioncontrol.versioncontrol.storage.ObjectNotFoundException;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;

@Service
public class CommitBrowsingService {
    private static final int MAX_PAGE_SIZE = 100;
    // MySQL's default recursive CTE depth is 1,000. Keep the deepest fetched row below that limit.
    private static final int MAX_HISTORY_DEPTH = 999;

    private final BranchRepository branches;
    private final CommitRepository commits;
    private final TreeEntryRepository entries;
    private final RepositoryAccessService access;
    private final ObjectStorage storage;

    public CommitBrowsingService(BranchRepository branches,
            CommitRepository commits, TreeEntryRepository entries,
            RepositoryAccessService access, ObjectStorage storage) {
        this.branches = branches;
        this.commits = commits;
        this.entries = entries;
        this.access = access;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public CommitHistory history(Long repositoryId, String branchName, int page, int size, Long userId) {
        long offsetValue = (long) page * size;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || offsetValue + size > MAX_HISTORY_DEPTH) {
            throw new InvalidRequestException("history page is out of range; size must be between 1 and 100");
        }
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, userId);
        if (!branches.existsByRepository_IdAndName(repositoryId, branchName)) {
            throw new ResourceNotFoundException("Branch not found");
        }
        int offset = (int) offsetValue;
        List<CommitRepository.HistoryRow> rows = commits.findHistoryPage(repositoryId, branchName,
                offset, offset + size);
        boolean hasMore = rows.size() > size;
        List<CommitHistoryItem> result = rows.stream().limit(size)
                .map(row -> new CommitHistoryItem(row.getCommitHash(), row.getParentCommitHash(), row.getMessage(),
                        new Author(row.getAuthorId(), row.getAuthorUsername()), row.getRootTreeHash(), row.getCreatedAt()))
                .toList();
        return new CommitHistory(page, size, hasMore, result);
    }

    @Transactional(readOnly = true)
    public CommitMetadata getCommit(String hash, Long userId) {
        return toMetadata(findReadableCommit(hash, userId), null);
    }

    @Transactional(readOnly = true)
    public TreeListing rootTree(String hash, Long userId) {
        Commit commit = findReadableCommit(hash, userId);
        return listing(commit, commit.getRootTree(), "");
    }

    @Transactional(readOnly = true)
    public TreeListing directory(String hash, String rawPath, Long userId) {
        Commit commit = findReadableCommit(hash, userId);
        String path = normalizeCapturedPath(rawPath);
        Tree tree = commit.getRootTree();
        for (String segment : path.split("/", -1)) {
            TreeEntry entry = entries.findByTree_IdAndName(tree.getId(), segment)
                    .orElseThrow(() -> new ResourceNotFoundException("Path not found"));
            if (entry.getEntryType() != TreeEntryType.DIRECTORY) {
                throw new ResourceNotFoundException("Path not found");
            }
            tree = entry.getChildTree();
        }
        return listing(commit, tree, path);
    }

    @Transactional(readOnly = true)
    public BlobMetadata blob(String hash, String rawPath, Long userId) {
        Commit commit = findReadableCommit(hash, userId);
        String path = normalizeCapturedPath(rawPath);
        String[] segments = path.split("/", -1);
        Tree tree = commit.getRootTree();
        for (int i = 0; i < segments.length; i++) {
            TreeEntry entry = entries.findByTree_IdAndName(tree.getId(), segments[i])
                    .orElseThrow(() -> new ResourceNotFoundException("Path not found"));
            if (i < segments.length - 1) {
                if (entry.getEntryType() != TreeEntryType.DIRECTORY) {
                    throw new ResourceNotFoundException("Path not found");
                }
                tree = entry.getChildTree();
            } else {
                if (entry.getEntryType() != TreeEntryType.FILE) {
                    throw new ResourceNotFoundException("Path not found");
                }
                var blob = entry.getBlob();
                if (!storage.exists(blob.getObjectHash())) throw new ObjectNotFoundException(blob.getObjectHash());
                return new BlobMetadata(blob.getObjectHash(), blob.getSize());
            }
        }
        throw new ResourceNotFoundException("Path not found");
    }

    public InputStream openBlob(String hash) {
        return storage.read(hash);
    }

    private Commit findReadableCommit(String hash, Long userId) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new InvalidRequestException("Commit hash must be 64 lowercase hexadecimal characters");
        }
        Commit commit = commits.findDetailedByCommitHash(hash)
                .orElseThrow(() -> new ResourceNotFoundException("Commit not found"));
        access.requireReadable(commit.getRepository(), userId);
        return commit;
    }

    private CommitMetadata toMetadata(Commit commit, Long historyRepositoryId) {
        return new CommitMetadata(commit.getCommitHash(),
                commit.getParentCommit() == null ? null : commit.getParentCommit().getCommitHash(),
                commit.getMessage(), new Author(commit.getAuthor().getId(), commit.getAuthor().getUsername()),
                historyRepositoryId == null ? commit.getRepository().getId() : historyRepositoryId,
                commit.getRootTree().getTreeHash(), commit.getCreatedAt());
    }

    private TreeListing listing(Commit commit, Tree tree, String path) {
        List<TreeEntry> found = entries.findByTree_Id(tree.getId());
        found.sort(Comparator.comparing(TreeEntry::getName, CommitBrowsingService::compareUtf8));
        List<TreeEntryItem> items = found.stream().map(entry -> entry.getEntryType() == TreeEntryType.FILE
                ? new TreeEntryItem(entry.getName(), "FILE", entry.getBlob().getObjectHash(), entry.getBlob().getSize(), null)
                : new TreeEntryItem(entry.getName(), "DIRECTORY", null, null, entry.getChildTree().getTreeHash()))
                .toList();
        return new TreeListing(commit.getCommitHash(), tree.getTreeHash(), path, items);
    }

    private static int compareUtf8(String left, String right) {
        byte[] a = Normalizer.normalize(left, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
        byte[] b = Normalizer.normalize(right, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int difference = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (difference != 0) return difference;
        }
        return Integer.compare(a.length, b.length);
    }

    private String normalizeCapturedPath(String rawPath) {
        // Spring's { *path } catch-all includes one leading slash in the variable.
        String path = rawPath != null && rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (path == null || path.isEmpty() || path.startsWith("/") || path.endsWith("/")
                || path.indexOf('\\') >= 0 || path.matches("^[A-Za-z]:.*")) {
            throw new InvalidRequestException("Path must be a relative slash-separated path");
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = Normalizer.normalize(segments[i], Normalizer.Form.NFC);
            if (!TreeEntryNameValidator.isValidName(segments[i])) {
                throw new InvalidRequestException("Path contains an invalid segment");
            }
        }
        return String.join("/", segments);
    }
}
