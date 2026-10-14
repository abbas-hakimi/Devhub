package com.versioncontrol.repository;

import com.versioncontrol.entity.Project;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> lockById(@Param("id") Long id);
    @Override @EntityGraph(attributePaths = {"owner", "repository", "repository.owner"})
    Optional<Project> findById(Long id);
    @EntityGraph(attributePaths = {"owner", "repository", "repository.owner"})
    List<Project> findAllByOrderByUpdatedAtDescIdDesc();
    @EntityGraph(attributePaths = {"owner", "repository", "repository.owner"})
    List<Project> findByOwner_IdAndScopeOrderByUpdatedAtDescIdDesc(Long ownerId, com.versioncontrol.entity.ProjectScope scope);
    @EntityGraph(attributePaths = {"owner", "repository", "repository.owner"})
    List<Project> findByRepository_IdInOrderByUpdatedAtDescIdDesc(List<Long> repositoryIds);
    boolean existsByOwner_IdAndScopeAndNameIgnoreCase(Long ownerId, com.versioncontrol.entity.ProjectScope scope, String name);
    boolean existsByRepository_IdAndNameIgnoreCase(Long repositoryId, String name);
}
