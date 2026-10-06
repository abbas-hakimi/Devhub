package com.versioncontrol.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import com.versioncontrol.entity.IssueLabel;

public interface IssueLabelRepository extends JpaRepository<IssueLabel, Long> {
    @EntityGraph(attributePaths = {"label", "label.repository", "label.repository.owner"})
    List<IssueLabel> findByIssue_IdOrderByLabel_NameAsc(Long issueId);
    @EntityGraph(attributePaths = {"label", "label.repository", "label.repository.owner"})
    List<IssueLabel> findByIssue_IdIn(Collection<Long> issueIds);
    boolean existsByIssue_IdAndLabel_Id(Long issueId, Long labelId);
    void deleteByIssue_IdAndLabel_Id(Long issueId, Long labelId);
}
