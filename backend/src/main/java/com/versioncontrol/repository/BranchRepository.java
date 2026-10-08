package com.versioncontrol.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.Branch;

public interface BranchRepository extends JpaRepository<Branch, Long> {
    @EntityGraph(attributePaths = {"repository", "headCommit", "headCommit.rootTree"})
    Optional<Branch> findByRepository_IdAndName(Long repositoryId, String name);

    @EntityGraph(attributePaths = {"repository", "headCommit", "headCommit.author", "headCommit.rootTree", "headCommit.parentCommit"})
    java.util.List<Branch> findByRepository_IdOrderByNameAsc(Long repositoryId);

    @EntityGraph(attributePaths = {"repository", "headCommit", "headCommit.author", "headCommit.rootTree", "headCommit.parentCommit"})
    Optional<Branch> findWithDetailsById(Long id);

    boolean existsByRepository_IdAndName(Long repositoryId, String name);

    long countByRepository_IdAndName(Long repositoryId, String name);
}
