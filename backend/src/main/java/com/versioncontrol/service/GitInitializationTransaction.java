package com.versioncontrol.service;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.Tree;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.TreeRepository;
import com.versioncontrol.service.RepositoryAccessService;
import com.versioncontrol.versioncontrol.hash.CommitCanonicalizer;
import com.versioncontrol.versioncontrol.hash.TreeCanonicalizer;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;

@Service
public class GitInitializationTransaction {
    private static final String MAIN_BRANCH = "main";
    private static final String INITIAL_MESSAGE = "Initial commit";

    private final RepositoryRepository repositories;
    private final BranchRepository branches;
    private final CommitRepository commits;
    private final TreeRepository trees;
    private final RepositoryAccessService repositoryAccess;
    private final ObjectStorage objectStorage;

    public GitInitializationTransaction(RepositoryRepository repositories, BranchRepository branches,
            CommitRepository commits, TreeRepository trees, RepositoryAccessService repositoryAccess,
            ObjectStorage objectStorage) {
        this.repositories = repositories;
        this.branches = branches;
        this.commits = commits;
        this.trees = trees;
        this.repositoryAccess = repositoryAccess;
        this.objectStorage = objectStorage;
    }

    @Transactional
    public GitInitializationResult initialize(Long repositoryId, Long authenticatedUserId) {
        Repository repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new com.versioncontrol.exception.ResourceNotFoundException("Repository not found"));
        repositoryAccess.requireOwner(repository, authenticatedUserId);

        var existingMain = branches.findByRepository_IdAndName(repositoryId, MAIN_BRANCH);
        if (existingMain.isPresent()) return GitInitializationResult.from(existingMain.get(), false);

        List<com.versioncontrol.versioncontrol.hash.TreeHashInput> emptyEntries = List.of();
        byte[] treeBytes = TreeCanonicalizer.canonicalize(emptyEntries);
        String treeHash = TreeCanonicalizer.hash(emptyEntries);
        objectStorage.write(treeHash, new ByteArrayInputStream(treeBytes), treeBytes.length);
        Tree rootTree = trees.findByTreeHash(treeHash).orElseGet(() -> trees.saveAndFlush(new Tree(treeHash)));

        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        byte[] commitBytes = CommitCanonicalizer.canonicalize(repositoryId, null, treeHash,
                authenticatedUserId, createdAt, INITIAL_MESSAGE);
        String commitHash = CommitCanonicalizer.hash(repositoryId, null, treeHash,
                authenticatedUserId, createdAt, INITIAL_MESSAGE);
        objectStorage.write(commitHash, new ByteArrayInputStream(commitBytes), commitBytes.length);

        LocalDateTime persistedTimestamp = LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        Commit initialCommit = commits.saveAndFlush(new Commit(repository, commitHash, INITIAL_MESSAGE,
                repository.getOwner(), rootTree, null, persistedTimestamp));
        Branch main = branches.saveAndFlush(new Branch(repository, MAIN_BRANCH, initialCommit));
        return GitInitializationResult.from(main, true);
    }
}
