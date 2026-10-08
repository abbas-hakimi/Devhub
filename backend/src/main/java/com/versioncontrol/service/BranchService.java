package com.versioncontrol.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.versioncontrol.dto.git.BranchResponse;
import com.versioncontrol.dto.git.CreateBranchRequest;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;

@Service
public class BranchService {
    private static final String MAIN_BRANCH = "main";

    private final BranchRepository branches;
    private final CommitRepository commits;
    private final RepositoryAccessService repositoryAccess;

    public BranchService(BranchRepository branches, CommitRepository commits, RepositoryAccessService repositoryAccess) {
        this.branches = branches;
        this.commits = commits;
        this.repositoryAccess = repositoryAccess;
    }

    @Transactional
    public BranchResponse create(Long repositoryId, CreateBranchRequest request, Long authenticatedUserId) {
        Repository repository = repositoryAccess.find(repositoryId);
        repositoryAccess.requireOwner(repository, authenticatedUserId);

        Branch main = branches.findByRepository_IdAndName(repositoryId, MAIN_BRANCH)
                .orElseThrow(() -> new RepositoryConflictException("Repository must be initialized before creating branches"));
        if (branches.existsByRepository_IdAndName(repositoryId, request.name())) {
            throw new RepositoryConflictException("A branch with this name already exists in the repository");
        }

        Commit head;
        if (request.fromCommitHash() == null) {
            head = main.getHeadCommit();
        } else {
            head = commits.findByCommitHash(request.fromCommitHash())
                    .filter(commit -> commit.getRepository().getId().equals(repositoryId))
                    .orElseThrow(() -> new ResourceNotFoundException("Source commit not found in this repository"));
        }

        try {
            Branch branch = branches.saveAndFlush(new Branch(repository, request.name(), head));
            return BranchResponse.from(branch);
        } catch (DataIntegrityViolationException duplicate) {
            throw new RepositoryConflictException("A branch with this name already exists in the repository");
        }
    }

    @Transactional(readOnly = true)
    public List<BranchResponse> list(Long repositoryId, Long authenticatedUserId) {
        Repository repository = repositoryAccess.find(repositoryId);
        repositoryAccess.requireReadable(repository, authenticatedUserId);
        return branches.findByRepository_IdOrderByNameAsc(repositoryId).stream().map(BranchResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public BranchResponse get(Long branchId, Long authenticatedUserId) {
        Branch branch = branches.findWithDetailsById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        repositoryAccess.requireReadable(branch.getRepository(), authenticatedUserId);
        return BranchResponse.from(branch);
    }
}
