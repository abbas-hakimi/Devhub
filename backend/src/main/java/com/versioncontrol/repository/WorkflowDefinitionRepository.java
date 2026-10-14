package com.versioncontrol.repository;

import com.versioncontrol.entity.WorkflowDefinition;
import com.versioncontrol.entity.WorkflowTriggerType;
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

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, Long> {
    @EntityGraph(attributePaths = {"repository", "repository.owner", "createdBy"})
    Optional<WorkflowDefinition> findById(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowDefinition w join fetch w.repository where w.id = :id")
    Optional<WorkflowDefinition> lockById(@Param("id") Long id);
    @EntityGraph(attributePaths = {"repository", "repository.owner", "createdBy"})
    Page<WorkflowDefinition> findByRepository_IdOrderByCreatedAtDescIdDesc(Long repositoryId, Pageable pageable);
    List<WorkflowDefinition> findByRepository_IdAndEnabledTrueAndTriggerType(Long repositoryId, WorkflowTriggerType triggerType);
    boolean existsByRepository_IdAndNameIgnoreCase(Long repositoryId, String name);
}
