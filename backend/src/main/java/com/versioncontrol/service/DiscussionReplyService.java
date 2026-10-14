package com.versioncontrol.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.versioncontrol.dto.discussion.*;
import com.versioncontrol.entity.*;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.*;

@Service
public class DiscussionReplyService {
    private final RepositoryDiscussionRepository discussions;
    private final DiscussionReplyRepository replies;
    private final DiscussionReplyReactionRepository reactions;
    private final UserRepository users;
    private final RepositoryAccessService access;
    private final NotificationService notifications;
    public DiscussionReplyService(RepositoryDiscussionRepository discussions, DiscussionReplyRepository replies,
            DiscussionReplyReactionRepository reactions, UserRepository users, RepositoryAccessService access,
            NotificationService notifications) {
        this.discussions = discussions; this.replies = replies; this.reactions = reactions; this.users = users; this.access = access;
        this.notifications = notifications;
    }

    @Transactional
    public DiscussionReplyResponse create(Long discussionId, CreateReplyRequest request, Long actorId) {
        RepositoryDiscussion discussion = findDiscussionLocked(discussionId);
        access.requireReadable(discussion.getRepository(), actorId);
        access.requireWrite(discussion.getRepository(), actorId);
        if (discussion.getState() == DiscussionState.CLOSED) throw new RepositoryConflictException("Closed discussions cannot receive replies");
        DiscussionReply parent = null;
        if (request.parentReplyId() != null) {
            parent = replies.findDetailedById(request.parentReplyId()).orElseThrow(() -> new ResourceNotFoundException("Parent reply not found"));
            if (!parent.getDiscussion().getId().equals(discussionId)) throw new InvalidRequestException("Parent reply must belong to this discussion");
            if (parent.getParent() != null) throw new InvalidRequestException("Replies may be nested only one level");
        }
        User author = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        DiscussionReply reply = replies.saveAndFlush(new DiscussionReply(discussion, author, parent, request.body().trim()));
        validateParentRelationship(reply.getId(), parent == null ? null : parent.getId());
        java.util.Set<Long> recipients = new java.util.LinkedHashSet<>();
        recipients.add(discussion.getAuthor().getId());
        if (parent != null) recipients.add(parent.getAuthor().getId());
        notifications.notifyRecipients(recipients, actorId, discussion.getRepository(),
                parent == null ? com.versioncontrol.entity.NotificationEventType.DISCUSSION_REPLIED
                        : com.versioncontrol.entity.NotificationEventType.DISCUSSION_REPLY_REPLIED,
                "New discussion reply", "A reply was added to " + discussion.getTitle(), "DISCUSSION", discussion.getId(),
                "DISCUSSION_REPLY:" + reply.getId());
        return response(reply);
    }

    @Transactional(readOnly = true)
    public DiscussionReplyPageResponse list(Long discussionId, int page, int size, Long actorId) {
        RepositoryDiscussion discussion = discussions.findDetailedById(discussionId)
                .orElseThrow(() -> new ResourceNotFoundException("Discussion not found"));
        access.requireReadable(discussion.getRepository(), actorId);
        DiscussionService.validatePage(page, size);
        Page<DiscussionReply> result = replies.pageByDiscussion(discussionId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"))));
        List<Long> ids = result.getContent().stream().map(DiscussionReply::getId).toList();
        Map<Long, Map<String, Long>> counts = reactionCounts(ids);
        return new DiscussionReplyPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.hasNext(), result.getContent().stream().map(r -> DiscussionReplyResponse.from(r,
                        counts.getOrDefault(r.getId(), DiscussionService.emptyCounts()))).toList());
    }

    @Transactional
    public DiscussionReplyResponse update(Long replyId, UpdateReplyRequest request, Long actorId) {
        DiscussionReply reply = findReplyLocked(replyId);
        access.requireReadable(reply.getDiscussion().getRepository(), actorId);
        requireAuthorOrMaintainer(reply, actorId);
        reply.setBody(request.body().trim());
        return response(replies.saveAndFlush(reply));
    }

    @Transactional
    public void delete(Long replyId, Long actorId) {
        DiscussionReply reply = findReplyLocked(replyId);
        access.requireReadable(reply.getDiscussion().getRepository(), actorId);
        requireAuthorOrMaintainer(reply, actorId);
        if (replies.existsByParent_Id(replyId)) throw new RepositoryConflictException("Reply has child replies and cannot be deleted yet");
        if (reply.getDiscussion().getAcceptedReplyId() != null && reply.getDiscussion().getAcceptedReplyId().equals(replyId)) {
            RepositoryDiscussion discussion = discussions.findByIdForUpdate(reply.getDiscussion().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Discussion not found"));
            discussion.setAcceptedReplyId(null);
            discussions.saveAndFlush(discussion);
        }
        reactions.deleteByReply_Id(replyId);
        replies.delete(reply);
        replies.flush();
    }

    @Transactional
    public void addReaction(Long replyId, DiscussionReactionType type, Long actorId) {
        DiscussionReply reply = findReplyLocked(replyId);
        access.requireReadable(reply.getDiscussion().getRepository(), actorId);
        if (reactions.findByReply_IdAndUser_IdAndType(replyId, actorId, type).isEmpty()) {
            User user = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
            reactions.saveAndFlush(new DiscussionReplyReaction(reply, user, type));
        }
    }

    @Transactional
    public void removeReaction(Long replyId, DiscussionReactionType type, Long actorId) {
        DiscussionReply reply = findReplyLocked(replyId);
        access.requireReadable(reply.getDiscussion().getRepository(), actorId);
        reactions.deleteByReply_IdAndUser_IdAndType(replyId, actorId, type);
    }

    private DiscussionReplyResponse response(DiscussionReply reply) {
        return DiscussionReplyResponse.from(reply, oneCounts(reply.getId()));
    }
    private Map<String, Long> oneCounts(Long id) {
        Map<String, Long> counts = DiscussionService.emptyCounts();
        for (Object[] row : reactions.countsForReplies(List.of(id)))
            counts.put(((DiscussionReactionType) row[1]).name(), ((Number) row[2]).longValue());
        return counts;
    }
    private Map<Long, Map<String, Long>> reactionCounts(List<Long> ids) {
        Map<Long, Map<String, Long>> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        for (Long id : ids) result.put(id, DiscussionService.emptyCounts());
        for (Object[] row : reactions.countsForReplies(ids))
            result.get(((Number) row[0]).longValue()).put(((DiscussionReactionType) row[1]).name(), ((Number) row[2]).longValue());
        return result;
    }
    private RepositoryDiscussion findDiscussionLocked(Long id) {
        return discussions.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Discussion not found"));
    }
    private DiscussionReply findReplyLocked(Long id) {
        return replies.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Reply not found"));
    }
    private void requireAuthorOrMaintainer(DiscussionReply r, Long actorId) {
        if (!r.getAuthor().getId().equals(actorId) && !access.hasMaintain(r.getDiscussion().getRepository(), actorId))
            throw new ResourceForbiddenException("Only the reply author or a repository maintainer can perform this action");
    }

    static void validateParentRelationship(Long replyId, Long parentReplyId) {
        if (replyId != null && replyId.equals(parentReplyId))
            throw new InvalidRequestException("A reply cannot reference itself as its parent");
    }
}
