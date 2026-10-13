package com.versioncontrol.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.PullRequestState;

public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {
    @EntityGraph(attributePaths = {"repository", "repository.owner", "author", "sourceBranch", "sourceBranch.headCommit",
            "targetBranch", "targetBranch.headCommit", "sourceHeadCommitAtCreation", "targetHeadCommitAtCreation", "mergedBy"})
    Optional<PullRequest> findDetailedById(Long id);

    @Query("select p.repository.id from PullRequest p where p.id = :id")
    Optional<Long> findRepositoryIdById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"repository", "repository.owner", "author", "sourceBranch", "sourceBranch.headCommit",
            "targetBranch", "targetBranch.headCommit", "sourceHeadCommitAtCreation", "targetHeadCommitAtCreation", "mergedBy"})
    @Query("select p from PullRequest p where p.repository.id = :repositoryId " +
            "and (:state is null or p.state = :state) and (:authorId is null or p.author.id = :authorId)")
    Page<PullRequest> search(@Param("repositoryId") Long repositoryId, @Param("state") PullRequestState state,
            @Param("authorId") Long authorId, Pageable pageable);

    @Query("select coalesce(max(p.number), 0) from PullRequest p where p.repository.id = :repositoryId")
    long findMaximumNumber(@Param("repositoryId") Long repositoryId);

    @Query("select count(p) from PullRequest p where p.repository.id = :repositoryId and p.state = :openState " +
            "and p.sourceBranch.id = :sourceId and p.targetBranch.id = :targetId and (:exceptId is null or p.id <> :exceptId)")
    long countOpenPair(@Param("repositoryId") Long repositoryId, @Param("sourceId") Long sourceId,
            @Param("targetId") Long targetId, @Param("exceptId") Long exceptId,
            @Param("openState") PullRequestState openState);

    long countByRepository_Id(Long repositoryId);
}
