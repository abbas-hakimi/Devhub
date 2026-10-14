package com.versioncontrol.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import com.versioncontrol.dto.git.CommitChangeRequest;
import com.versioncontrol.dto.git.CommitResponse;
import com.versioncontrol.dto.git.CreateCommitRequest;
import com.versioncontrol.entity.Blob;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.Tree;
import com.versioncontrol.entity.TreeEntry;
import com.versioncontrol.entity.TreeEntryType;
import com.versioncontrol.entity.User;
import com.versioncontrol.entity.WorkflowTriggerType;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.BlobRepository;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.TreeEntryRepository;
import com.versioncontrol.repository.TreeRepository;
import com.versioncontrol.repository.UserRepository;
import com.versioncontrol.validation.BranchNameValidator;
import com.versioncontrol.validation.TreeEntryNameValidator;
import com.versioncontrol.versioncontrol.hash.CommitCanonicalizer;
import com.versioncontrol.versioncontrol.hash.Sha256Hasher;
import com.versioncontrol.versioncontrol.hash.TreeCanonicalizer;
import com.versioncontrol.versioncontrol.hash.TreeHashInput;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;

@Service
public class CommitService {
    private static final String MAIN_BRANCH = "main";
    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_TOTAL_BYTES = 10_000_000;
    private static final int MAX_PATH_DEPTH = 64;

    private final RepositoryRepository repositories;
    private final BranchRepository branches;
    private final CommitRepository commits;
    private final TreeRepository trees;
    private final TreeEntryRepository treeEntries;
    private final BlobRepository blobs;
    private final RepositoryAccessService repositoryAccess;
    private final ObjectStorage storage;
    private final UserRepository users;
    private final RepositoryActivityService activity;
    private final ApplicationEventPublisher events;

    public CommitService(RepositoryRepository repositories, BranchRepository branches, CommitRepository commits,
            TreeRepository trees, TreeEntryRepository treeEntries, BlobRepository blobs,
            RepositoryAccessService repositoryAccess, ObjectStorage storage, UserRepository users,
            RepositoryActivityService activity, ApplicationEventPublisher events) {
        this.repositories = repositories;
        this.branches = branches;
        this.commits = commits;
        this.trees = trees;
        this.treeEntries = treeEntries;
        this.blobs = blobs;
        this.repositoryAccess = repositoryAccess;
        this.storage = storage;
        this.users = users;
        this.activity = activity;
        this.events = events;
    }

    @Transactional
    public CommitResponse create(Long repositoryId, CreateCommitRequest request, Long userId) {
        // Serialize operations that can create globally shared immutable tree/blob metadata.
        Repository repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        repositoryAccess.requireWrite(repository, userId);
        if (!BranchNameValidator.isValidName(request.branch())) {
            throw new InvalidRequestException("Branch name is invalid");
        }
        Branch branch = branches.findByRepositoryAndNameForUpdate(repositoryId, request.branch())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        Commit parent = branch.getHeadCommit();
        if (request.expectedHeadHash() != null && !request.expectedHeadHash().equals(parent.getCommitHash())) {
            throw new RepositoryConflictException("Branch HEAD changed; fetch the latest commit and retry");
        }

        Map<String, Blob> snapshot = new LinkedHashMap<>();
        flatten(parent.getRootTree(), "", snapshot, new HashSet<>());
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        List<String> deletions = new ArrayList<>();
        Set<String> allPaths = new HashSet<>();
        long totalBytes = 0;

        for (CommitChangeRequest change : nullSafe(request.changes())) {
            if (change == null || !change.hasExactlyOneContentSource()) {
                throw new InvalidRequestException("Each change must provide exactly one of content or contentBase64");
            }
            String path = normalizePath(change.path());
            if (!allPaths.add(path)) throw new InvalidRequestException("A path may appear only once in a commit request");
            byte[] content;
            try {
                content = change.content() != null
                        ? change.content().getBytes(StandardCharsets.UTF_8)
                        : Base64.getDecoder().decode(change.contentBase64());
            } catch (IllegalArgumentException malformedBase64) {
                throw new InvalidRequestException("contentBase64 must contain valid Base64 data");
            }
            if (content.length > MAX_FILE_BYTES) throw new InvalidRequestException("Each file is limited to 1 MB");
            totalBytes += content.length;
            if (totalBytes > MAX_TOTAL_BYTES) throw new InvalidRequestException("Total commit content is limited to 10 MB");
            replacements.put(path, content);
        }
        for (String rawPath : nullSafe(request.deletedPaths())) {
            String path = normalizePath(rawPath);
            if (!allPaths.add(path)) throw new InvalidRequestException("A path cannot be changed and deleted in the same commit");
            if (!snapshot.containsKey(path)) throw new InvalidRequestException("Cannot delete a path that does not exist: " + path);
            deletions.add(path);
        }

        Map<String, String> replacementHashes = new LinkedHashMap<>();
        for (var replacement : replacements.entrySet()) {
            replacementHashes.put(replacement.getKey(), Sha256Hasher.hashBytes(replacement.getValue()));
        }
        boolean changed = !deletions.isEmpty();
        for (var replacement : replacementHashes.entrySet()) {
            Blob existing = snapshot.get(replacement.getKey());
            if (existing == null || !existing.getObjectHash().equals(replacement.getValue())) changed = true;
        }
        if (!changed) throw new InvalidRequestException("Commit contains no changes");

        Map<String, Blob> resulting = new LinkedHashMap<>(snapshot);
        for (String path : deletions) resulting.remove(path);
        Set<String> resultingPaths = new HashSet<>(resulting.keySet());
        resultingPaths.addAll(replacements.keySet());
        validateSnapshotPaths(resultingPaths);
        Map<String, Blob> replacementBlobs = new HashMap<>();
        for (var replacement : replacements.entrySet()) {
            String path = replacement.getKey();
            byte[] content = replacement.getValue();
            String hash = replacementHashes.get(path);
            Blob blob = replacementBlobs.get(hash);
            if (blob == null) {
                storage.write(hash, new ByteArrayInputStream(content), content.length);
                blob = blobs.findByObjectHash(hash).orElse(null);
                if (blob == null) {
                    blob = blobs.saveAndFlush(new Blob(hash, content.length));
                } else if (blob.getSize() != content.length) {
                    throw new RepositoryConflictException("Stored blob metadata conflicts with its content hash");
                }
                replacementBlobs.put(hash, blob);
            }
            resulting.put(path, blob);
        }
        Tree newRoot = buildTree(resulting);
        if (newRoot.getTreeHash().equals(parent.getRootTree().getTreeHash())) {
            throw new InvalidRequestException("Commit contains no changes");
        }

        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant parentTime = parent.getCreatedAt().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (!createdAt.isAfter(parentTime)) createdAt = parentTime.plus(1, ChronoUnit.MICROS);
        String parentHash = parent.getCommitHash();
        String commitHash = CommitCanonicalizer.hash(repositoryId, parentHash, newRoot.getTreeHash(), userId,
                createdAt, request.message());
        byte[] commitBytes = CommitCanonicalizer.canonicalize(repositoryId, parentHash, newRoot.getTreeHash(), userId,
                createdAt, request.message());
        storage.write(commitHash, new ByteArrayInputStream(commitBytes), commitBytes.length);

        LocalDateTime persistedAt = LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        Commit commit;
        try {
            User author = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
            commit = commits.saveAndFlush(new Commit(repository, commitHash, request.message(), author,
                    newRoot, parent, persistedAt));
            branch.setHeadCommit(commit);
            branches.saveAndFlush(branch);
            events.publishEvent(new WorkflowTriggerEvent(repositoryId, WorkflowTriggerType.PUSH, userId,
                    branch.getName(), commit.getCommitHash(), "push:" + repositoryId + ":" + branch.getId() + ":" + commit.getCommitHash()));
            activity.record(repository, userId, com.versioncontrol.entity.RepositoryActivityEventType.COMMIT_CREATED,
                    "COMMIT", commit.getId(), "Created commit " + commit.getCommitHash().substring(0, 12), "COMMIT_CREATED:" + commit.getId());
        } catch (DataIntegrityViolationException duplicate) {
            throw new RepositoryConflictException("Commit conflicted with another repository update; retry the request");
        }
        return new CommitResponse(repositoryId, branch.getName(), commit.getCommitHash(), parentHash,
                newRoot.getTreeHash(), commit.getMessage(), "COMMITTED");
    }

    private void flatten(Tree tree, String prefix, Map<String, Blob> files, Set<Long> visiting) {
        if (!visiting.add(tree.getId())) throw new RepositoryConflictException("Stored tree graph contains a cycle");
        for (TreeEntry entry : treeEntries.findByTree_IdOrderByNameAsc(tree.getId())) {
            String path = prefix.isEmpty() ? entry.getName() : prefix + "/" + entry.getName();
            if (entry.getEntryType() == TreeEntryType.FILE) files.put(path, entry.getBlob());
            else flatten(entry.getChildTree(), path, files, visiting);
        }
        visiting.remove(tree.getId());
    }

    private Tree buildTree(Map<String, Blob> files) {
        Directory root = new Directory();
        for (var file : files.entrySet()) {
            String[] segments = file.getKey().split("/", -1);
            Directory current = root;
            for (int i = 0; i < segments.length - 1; i++) {
                String segment = segments[i];
                Object prior = current.children.get(segment);
                if (prior instanceof Blob) throw new InvalidRequestException("A file path cannot also be a parent directory: " + file.getKey());
                if (prior == null) {
                    prior = new Directory();
                    current.children.put(segment, prior);
                }
                current = (Directory) prior;
            }
            String filename = segments[segments.length - 1];
            if (current.children.get(filename) instanceof Directory) {
                throw new InvalidRequestException("A directory path cannot also be a file: " + filename);
            }
            current.children.put(filename, file.getValue());
        }
        return persistDirectory(root);
    }

    private Tree persistDirectory(Directory directory) {
        List<TreeHashInput> inputs = new ArrayList<>(directory.children.size());
        Map<String, Tree> childTrees = new HashMap<>();
        for (var child : directory.children.entrySet()) {
            if (child.getValue() instanceof Blob blob) {
                inputs.add(new TreeHashInput(child.getKey(), TreeEntryType.FILE, blob.getObjectHash()));
            } else {
                Tree childTree = persistDirectory((Directory) child.getValue());
                childTrees.put(child.getKey(), childTree);
                inputs.add(new TreeHashInput(child.getKey(), TreeEntryType.DIRECTORY, childTree.getTreeHash()));
            }
        }
        byte[] canonical = TreeCanonicalizer.canonicalize(inputs);
        String hash = Sha256Hasher.hashBytes(canonical);
        Tree existing = trees.findByTreeHash(hash).orElse(null);
        if (existing != null) return existing;

        storage.write(hash, new ByteArrayInputStream(canonical), canonical.length);
        Tree tree = trees.saveAndFlush(new Tree(hash));
        List<TreeEntry> entries = new ArrayList<>(directory.children.size());
        for (var child : directory.children.entrySet()) {
            if (child.getValue() instanceof Blob blob) {
                entries.add(new TreeEntry(tree, child.getKey(), TreeEntryType.FILE, blob, null));
            } else {
                entries.add(new TreeEntry(tree, child.getKey(), TreeEntryType.DIRECTORY, null, childTrees.get(child.getKey())));
            }
        }
        treeEntries.saveAll(entries);
        treeEntries.flush();
        return tree;
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.endsWith("/")
                || path.indexOf('\\') >= 0 || path.matches("^[A-Za-z]:.*")) {
            throw new InvalidRequestException("Path must be a relative slash-separated file path");
        }
        String[] rawSegments = path.split("/", -1);
        if (rawSegments.length > MAX_PATH_DEPTH) {
            throw new InvalidRequestException("Path exceeds the maximum nesting depth");
        }
        List<String> segments = new ArrayList<>(rawSegments.length);
        for (String raw : rawSegments) {
            String segment = Normalizer.normalize(raw, Normalizer.Form.NFC);
            if (!TreeEntryNameValidator.isValidName(segment)) {
                throw new InvalidRequestException("Path contains an invalid segment");
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    private void validateSnapshotPaths(Set<String> paths) {
        for (String path : paths) {
            int slash = path.indexOf('/');
            while (slash >= 0) {
                if (paths.contains(path.substring(0, slash))) {
                    throw new InvalidRequestException("A file path cannot also be a parent directory: " + path);
                }
                slash = path.indexOf('/', slash + 1);
            }
        }
    }

    private <T> List<T> nullSafe(List<T> values) { return values == null ? List.of() : values; }

    private static final class Directory {
        private final Map<String, Object> children = new TreeMap<>();
    }
}
