package com.versioncontrol.repository;

import com.versioncontrol.entity.ProjectCard;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ProjectCardRepository extends JpaRepository<ProjectCard, Long> {
    @EntityGraph(attributePaths = {"project", "project.owner", "project.repository", "project.repository.owner", "column", "issue", "issue.repository", "pullRequest", "pullRequest.repository"})
    @Query("select c from ProjectCard c where c.project.id = :projectId order by c.column.position, c.position, c.id")
    Page<ProjectCard> pageByProject(@Param("projectId") Long projectId, Pageable pageable);
    @EntityGraph(attributePaths = {"project", "project.owner", "project.repository", "project.repository.owner", "column", "issue", "issue.repository", "pullRequest", "pullRequest.repository"})
    @Query("select c from ProjectCard c where c.project.id = :projectId order by c.column.position, c.position, c.id")
    List<ProjectCard> findBoardByProject(@Param("projectId") Long projectId);
    @EntityGraph(attributePaths = {"project", "project.owner", "project.repository", "project.repository.owner", "column", "issue", "issue.repository", "pullRequest", "pullRequest.repository"})
    Optional<ProjectCard> findById(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ProjectCard c where c.id = :id")
    Optional<ProjectCard> lockById(@Param("id") Long id);
    List<ProjectCard> findByColumn_IdOrderByPositionAscIdAsc(Long columnId);
    long countByColumn_Id(Long columnId);
    long countByProject_Id(Long projectId);
    boolean existsByProject_IdAndIssue_Id(Long projectId, Long issueId);
    boolean existsByProject_IdAndPullRequest_Id(Long projectId, Long pullRequestId);
}
