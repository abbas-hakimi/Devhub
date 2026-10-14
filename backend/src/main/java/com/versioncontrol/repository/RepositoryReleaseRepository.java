package com.versioncontrol.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.RepositoryRelease;

public interface RepositoryReleaseRepository extends JpaRepository<RepositoryRelease, Long> {
    boolean existsByRepository_IdAndTag_Id(Long repositoryId, Long tagId);
    boolean existsByRepository_IdAndTag_Name(Long repositoryId, String tagName);

    @EntityGraph(attributePaths = {"repository", "tag", "tag.commit", "createdBy"})
    Page<RepositoryRelease> findByRepository_IdOrderByCreatedAtDescIdDesc(Long repositoryId, Pageable pageable);

    @EntityGraph(attributePaths = {"repository", "tag", "tag.commit", "createdBy"})
    Page<RepositoryRelease> findByRepository_IdAndDraftFalseOrderByPublishedAtDescIdDesc(Long repositoryId, Pageable pageable);

    @EntityGraph(attributePaths = {"repository", "tag", "tag.commit", "createdBy"})
    Optional<RepositoryRelease> findDetailedByRepository_IdAndId(Long repositoryId, Long id);
}
