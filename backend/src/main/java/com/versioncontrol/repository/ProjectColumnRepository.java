package com.versioncontrol.repository;

import com.versioncontrol.entity.ProjectColumn;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ProjectColumnRepository extends JpaRepository<ProjectColumn, Long> {
    @EntityGraph(attributePaths = {"project", "project.owner", "project.repository", "project.repository.owner"})
    List<ProjectColumn> findByProject_IdOrderByPositionAscIdAsc(Long projectId);
    @EntityGraph(attributePaths = {"project", "project.owner", "project.repository", "project.repository.owner"})
    Optional<ProjectColumn> findById(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ProjectColumn c where c.id = :id")
    Optional<ProjectColumn> lockById(@Param("id") Long id);
    boolean existsByProject_IdAndNameIgnoreCase(Long projectId, String name);
    long countByProject_Id(Long projectId);
}
