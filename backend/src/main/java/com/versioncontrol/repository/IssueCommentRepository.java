package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import com.versioncontrol.entity.IssueComment;

public interface IssueCommentRepository extends JpaRepository<IssueComment, Long> {
    @Override @EntityGraph(attributePaths = {"issue", "author", "issue.repository", "issue.author", "issue.assignee"})
    Optional<IssueComment> findById(Long id);
    @EntityGraph(attributePaths = {"author", "issue"})
    List<IssueComment> findByIssue_IdOrderByCreatedAtAsc(Long issueId);
}
