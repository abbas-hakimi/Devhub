package com.versioncontrol.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.RepositoryTag;

public interface RepositoryTagRepository extends JpaRepository<RepositoryTag, Long> {
    boolean existsByRepository_IdAndName(Long repositoryId, String name);
    @EntityGraph(attributePaths = {"repository", "commit", "createdBy"})
    Page<RepositoryTag> findByRepository_IdOrderByNameAsc(Long repositoryId, Pageable pageable);

    @EntityGraph(attributePaths = {"repository", "commit", "createdBy"})
    Optional<RepositoryTag> findDetailedByRepository_IdAndName(Long repositoryId, String name);

    Optional<RepositoryTag> findByRepository_IdAndName(Long repositoryId, String name);
}
