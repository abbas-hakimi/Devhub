package com.versioncontrol.repository;

import com.versioncontrol.entity.WorkflowJob;
import com.versioncontrol.entity.WorkflowJobStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface WorkflowJobRepository extends JpaRepository<WorkflowJob, Long> {
    @EntityGraph(attributePaths = {"run", "run.workflow", "run.workflow.repository", "run.workflow.repository.owner"})
    Optional<WorkflowJob> findDetailedById(Long id);
    @EntityGraph(attributePaths = {"run", "run.workflow", "run.workflow.repository"})
    List<WorkflowJob> findByRun_IdOrderBySequenceNumberAsc(Long runId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from WorkflowJob j where j.id = :id")
    Optional<WorkflowJob> lockById(@Param("id") Long id);
    List<WorkflowJob> findByRun_IdAndStatusOrderBySequenceNumberAsc(Long runId, WorkflowJobStatus status);
    long countByRun_Id(Long runId);
}
