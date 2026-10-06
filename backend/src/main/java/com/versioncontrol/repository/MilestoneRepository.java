package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.versioncontrol.entity.Milestone;

public interface MilestoneRepository extends JpaRepository<Milestone, Long> {
    @Query("select m from Milestone m where m.repository.id = :repositoryId "
            + "order by case when m.dueDate is null then 1 else 0 end, m.dueDate asc, m.title asc")
    List<Milestone> findByRepositoryIdOrderByDueDateAscTitleAsc(@Param("repositoryId") Long repositoryId);
    @Query("select count(m) from Milestone m where m.repository.id = :repositoryId "
            + "and lower(trim(m.title)) = lower(trim(:title))")
    long countByRepositoryAndNormalizedTitle(@Param("repositoryId") Long repositoryId, @Param("title") String title);
    @Query("select count(m) from Milestone m where m.repository.id = :repositoryId "
            + "and lower(trim(m.title)) = lower(trim(:title)) and m.id <> :exceptId")
    long countByRepositoryAndNormalizedTitleExcludingId(@Param("repositoryId") Long repositoryId,
            @Param("title") String title, @Param("exceptId") Long exceptId);
    @Override
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "repository")
    Optional<Milestone> findById(Long id);
    @Modifying
    @Query("update Issue i set i.milestone = null where i.milestone.id = :milestoneId")
    int clearIssueAssociations(@Param("milestoneId") Long milestoneId);
}
