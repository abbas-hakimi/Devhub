package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import com.versioncontrol.entity.Issue;
import com.versioncontrol.entity.IssueStatus;

public interface IssueRepository extends JpaRepository<Issue, Long> {
    @Override @EntityGraph(attributePaths = {"repository", "author", "assignee"})
    Optional<Issue> findById(Long id);

    @EntityGraph(attributePaths = {"repository", "author", "assignee"})
    List<Issue> findByRepository_IdOrderByCreatedAtDesc(Long repositoryId);

    @EntityGraph(attributePaths = {"repository", "author", "assignee"})
    List<Issue> findByRepository_IdAndStatusOrderByCreatedAtDesc(Long repositoryId, IssueStatus status);
}
