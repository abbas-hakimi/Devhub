package com.versioncontrol.repository;

import com.versioncontrol.entity.WorkflowRun;
import com.versioncontrol.entity.WorkflowRunStatus;
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

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {
    @EntityGraph(attributePaths = {"workflow", "workflow.repository", "workflow.repository.owner", "triggeredBy"})
    Optional<WorkflowRun> findDetailedById(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkflowRun r join fetch r.workflow w join fetch w.repository where r.id = :id")
    Optional<WorkflowRun> lockById(@Param("id") Long id);
    @EntityGraph(attributePaths = {"workflow", "workflow.repository", "workflow.repository.owner", "triggeredBy"})
    Page<WorkflowRun> findByWorkflow_IdOrderByCreatedAtDescIdDesc(Long workflowId, Pageable pageable);
    @EntityGraph(attributePaths = {"workflow", "workflow.repository", "workflow.repository.owner", "triggeredBy"})
    Page<WorkflowRun> findByWorkflow_Repository_IdOrderByCreatedAtDescIdDesc(Long repositoryId, Pageable pageable);
    Optional<WorkflowRun> findByWorkflow_IdAndDispatchKey(Long workflowId, String dispatchKey);
    long countByWorkflow_Id(Long workflowId);
    @Query("select r.id from WorkflowRun r where r.status in :statuses order by r.createdAt asc, r.id asc")
    List<Long> findProcessableIds(@Param("statuses") List<WorkflowRunStatus> statuses, Pageable pageable);
}
