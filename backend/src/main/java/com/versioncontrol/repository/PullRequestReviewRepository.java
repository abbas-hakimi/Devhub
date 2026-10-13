package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.PullRequestReview;

public interface PullRequestReviewRepository extends JpaRepository<PullRequestReview, Long> {
    @EntityGraph(attributePaths = {"reviewer", "pullRequest"})
    Page<PullRequestReview> findByPullRequest_Id(Long pullRequestId, Pageable pageable);

    @EntityGraph(attributePaths = {"reviewer", "pullRequest"})
    List<PullRequestReview> findByPullRequest_IdOrderByCreatedAtAscIdAsc(Long pullRequestId);

    @EntityGraph(attributePaths = {"reviewer", "pullRequest"})
    Optional<PullRequestReview> findDetailedByIdAndPullRequest_Id(Long id, Long pullRequestId);

    long countByPullRequest_Id(Long pullRequestId);
}
