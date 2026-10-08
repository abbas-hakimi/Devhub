package com.versioncontrol.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.versioncontrol.entity.Branch;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.repository.BranchRepository;

@Service
public class GitInitializationService {
    private final GitInitializationTransaction initializationTransaction;
    private final BranchRepository branches;

    public GitInitializationService(GitInitializationTransaction initializationTransaction, BranchRepository branches) {
        this.initializationTransaction = initializationTransaction;
        this.branches = branches;
    }

    public GitInitializationResult initialize(Long repositoryId, Long authenticatedUserId) {
        try {
            return initializationTransaction.initialize(repositoryId, authenticatedUserId);
        } catch (DataIntegrityViolationException race) {
            Branch main = branches.findByRepository_IdAndName(repositoryId, "main").orElse(null);
            if (main != null) {
                return GitInitializationResult.from(main, false);
            }
            throw new RepositoryConflictException("Repository initialization conflicted; retry the request");
        }
    }
}
