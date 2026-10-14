package com.versioncontrol.repository;

import com.versioncontrol.entity.WorkflowJobLog;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowJobLogRepository extends JpaRepository<WorkflowJobLog, Long> {
    @EntityGraph(attributePaths = {"job", "job.run", "job.run.workflow", "job.run.workflow.repository"})
    Page<WorkflowJobLog> findByJob_IdOrderBySequenceNumberAscIdAsc(Long jobId, Pageable pageable);
    Optional<WorkflowJobLog> findTopByJob_IdOrderBySequenceNumberDesc(Long jobId);
    long countByJob_Id(Long jobId);
}
