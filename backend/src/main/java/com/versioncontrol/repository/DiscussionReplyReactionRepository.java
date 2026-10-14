package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.versioncontrol.entity.DiscussionReplyReaction;
import com.versioncontrol.entity.DiscussionReactionType;

public interface DiscussionReplyReactionRepository extends JpaRepository<DiscussionReplyReaction, Long> {
    long countByReply_Id(Long replyId);
    Optional<DiscussionReplyReaction> findByReply_IdAndUser_IdAndType(Long replyId, Long userId, DiscussionReactionType type);
    void deleteByReply_IdAndUser_IdAndType(Long replyId, Long userId, DiscussionReactionType type);
    @Query("select r.reply.id, r.type, count(r) from DiscussionReplyReaction r where r.reply.id in :ids group by r.reply.id, r.type")
    List<Object[]> countsForReplies(@Param("ids") List<Long> ids);
    void deleteByReply_Id(Long replyId);
}
