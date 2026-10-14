package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.versioncontrol.entity.DiscussionReaction;
import com.versioncontrol.entity.DiscussionReactionType;

public interface DiscussionReactionRepository extends JpaRepository<DiscussionReaction, Long> {
    long countByDiscussion_Id(Long discussionId);
    Optional<DiscussionReaction> findByDiscussion_IdAndUser_IdAndType(Long discussionId, Long userId, DiscussionReactionType type);
    void deleteByDiscussion_IdAndUser_IdAndType(Long discussionId, Long userId, DiscussionReactionType type);
    @Query("select r.discussion.id, r.type, count(r) from DiscussionReaction r where r.discussion.id in :ids group by r.discussion.id, r.type")
    List<Object[]> countsForDiscussions(@Param("ids") List<Long> ids);
}
