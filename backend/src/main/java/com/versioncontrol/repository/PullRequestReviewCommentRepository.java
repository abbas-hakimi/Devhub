package com.versioncontrol.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.PullRequestReviewComment;

public interface PullRequestReviewCommentRepository extends JpaRepository<PullRequestReviewComment, Long> {
    @EntityGraph(attributePaths = {"author", "review", "review.reviewer"})
    Page<PullRequestReviewComment> findByPullRequestId(Long pullRequestId, Pageable pageable);

    @EntityGraph(attributePaths = {"author", "review", "review.reviewer", "review.pullRequest", "review.pullRequest.repository"})
    Optional<PullRequestReviewComment> findDetailedById(Long id);

    @EntityGraph(attributePaths = {"author", "review", "review.reviewer"})
    Page<PullRequestReviewComment> findByReview_Id(Long reviewId, Pageable pageable);

    @EntityGraph(attributePaths = {"author", "review", "review.reviewer"})
    List<PullRequestReviewComment> findByReview_IdInOrderByIdAsc(List<Long> reviewIds);
}
