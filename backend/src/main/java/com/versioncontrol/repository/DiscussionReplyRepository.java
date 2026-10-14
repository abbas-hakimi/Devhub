package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.versioncontrol.entity.DiscussionReply;

public interface DiscussionReplyRepository extends JpaRepository<DiscussionReply, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DiscussionReply r join fetch r.discussion join fetch r.author where r.id = :id")
    Optional<DiscussionReply> findByIdForUpdate(@Param("id") Long id);
    @Query(value = "select r from DiscussionReply r join fetch r.author left join fetch r.parent where r.discussion.id = :discussionId",
           countQuery = "select count(r) from DiscussionReply r where r.discussion.id = :discussionId")
    Page<DiscussionReply> pageByDiscussion(@Param("discussionId") Long discussionId, Pageable pageable);
    @Query("select r from DiscussionReply r join fetch r.author left join fetch r.parent where r.id = :id")
    Optional<DiscussionReply> findDetailedById(@Param("id") Long id);
    List<DiscussionReply> findByDiscussion_IdOrderByIdDesc(Long discussionId);
    boolean existsByParent_Id(Long parentId);
}
