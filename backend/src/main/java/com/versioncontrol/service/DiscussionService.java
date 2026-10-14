package com.versioncontrol.service;

import java.time.LocalDateTime;
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
public class DiscussionService {
    private static final int MAX_PAGE_SIZE = 100;
    private final RepositoryRepository repositories;
    private final RepositoryDiscussionRepository discussions;
    private final DiscussionReplyRepository replies;
    private final DiscussionReactionRepository reactions;
    private final DiscussionReplyReactionRepository replyReactions;
    private final UserRepository users;
    private final RepositoryAccessService access;

    public DiscussionService(RepositoryRepository repositories, RepositoryDiscussionRepository discussions,
            DiscussionReplyRepository replies, DiscussionReactionRepository reactions,
            DiscussionReplyReactionRepository replyReactions, UserRepository users, RepositoryAccessService access) {
        this.repositories = repositories; this.discussions = discussions; this.replies = replies;
        this.reactions = reactions; this.replyReactions = replyReactions; this.users = users; this.access = access;
    }

    @Transactional
    public DiscussionResponse create(Long repositoryId, CreateDiscussionRequest request, Long actorId) {
        Repository repository = repositories.findByIdForUpdate(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
        access.requireReadable(repository, actorId);
        if (request.category() == DiscussionCategory.ANNOUNCEMENTS) access.requireMaintain(repository, actorId);
        else access.requireWrite(repository, actorId);
        long number = discussions.maxNumber(repositoryId) + 1;
        User author = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        RepositoryDiscussion created = discussions.saveAndFlush(new RepositoryDiscussion(repository, number,
                request.category(), request.title().trim(), request.body().trim(), author));
        return response(created);
    }

    @Transactional(readOnly = true)
    public DiscussionPageResponse list(Long repositoryId, int page, int size, Long actorId) {
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        validatePage(page, size);
        Page<RepositoryDiscussion> result = discussions.pageByRepository(repositoryId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "number")));
        List<Long> ids = result.getContent().stream().map(RepositoryDiscussion::getId).toList();
        Map<Long, Map<String, Long>> counts = discussionCounts(ids);
        return new DiscussionPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.hasNext(), result.getContent().stream().map(d -> DiscussionResponse.from(d,
                        counts.getOrDefault(d.getId(), emptyCounts()))).toList());
    }

    @Transactional(readOnly = true)
    public DiscussionResponse get(Long id, Long actorId) {
        RepositoryDiscussion discussion = findDetailed(id);
        access.requireReadable(discussion.getRepository(), actorId);
        return response(discussion);
    }

    @Transactional
    public DiscussionResponse update(Long id, UpdateDiscussionRequest request, Long actorId) {
        RepositoryDiscussion discussion = findLocked(id);
        access.requireReadable(discussion.getRepository(), actorId);
        requireAuthorOrMaintainer(discussion, actorId);
        if (!request.isTitleSpecified() && !request.isBodySpecified()) throw new InvalidRequestException("Provide at least one discussion field to update");
        if (request.isTitleSpecified()) {
            if (request.getTitle() == null || request.getTitle().isBlank() || request.getTitle().trim().length() > 255)
                throw new InvalidRequestException("Title must contain 1 to 255 characters");
            discussion.setTitle(request.getTitle().trim());
        }
        if (request.isBodySpecified()) {
            if (request.getBody() == null || request.getBody().isBlank() || request.getBody().length() > 64000)
                throw new InvalidRequestException("Body must contain 1 to 64000 characters");
            discussion.setBody(request.getBody().trim());
        }
        return response(discussions.saveAndFlush(discussion));
    }

    @Transactional
    public DiscussionResponse close(Long id, Long actorId) { return changeState(id, DiscussionState.CLOSED, actorId); }
    @Transactional
    public DiscussionResponse reopen(Long id, Long actorId) { return changeState(id, DiscussionState.OPEN, actorId); }

    private DiscussionResponse changeState(Long id, DiscussionState state, Long actorId) {
        RepositoryDiscussion discussion = findLocked(id);
        access.requireReadable(discussion.getRepository(), actorId);
        requireAuthorOrMaintainer(discussion, actorId);
        discussion.setState(state);
        discussion.setClosedAt(state == DiscussionState.CLOSED ? LocalDateTime.now() : null);
        return response(discussions.saveAndFlush(discussion));
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        RepositoryDiscussion discussion = findLocked(id);
        access.requireReadable(discussion.getRepository(), actorId);
        access.requireMaintain(discussion.getRepository(), actorId);
        discussion.setAcceptedReplyId(null);
        discussions.saveAndFlush(discussion);
        List<DiscussionReply> existing = replies.findByDiscussion_IdOrderByIdDesc(id);
        for (DiscussionReply reply : existing) {
            replyReactions.deleteByReply_Id(reply.getId());
            replies.delete(reply);
        }
        replies.flush();
        discussions.delete(discussion);
        discussions.flush();
    }

    @Transactional
    public void acceptAnswer(Long discussionId, Long replyId, Long actorId) {
        RepositoryDiscussion discussion = findLocked(discussionId);
        access.requireReadable(discussion.getRepository(), actorId);
        requireAuthorOrMaintainer(discussion, actorId);
        if (discussion.getCategory() != DiscussionCategory.Q_AND_A)
            throw new InvalidRequestException("Accepted answers are available only for Q&A discussions");
        DiscussionReply reply = replies.findById(replyId).orElseThrow(() -> new ResourceNotFoundException("Reply not found"));
        if (!reply.getDiscussion().getId().equals(discussionId))
            throw new InvalidRequestException("Accepted answer must belong to this discussion");
        discussion.setAcceptedReplyId(replyId);
        discussions.saveAndFlush(discussion);
    }

    @Transactional
    public void clearAcceptedAnswer(Long discussionId, Long actorId) {
        RepositoryDiscussion discussion = findLocked(discussionId);
        access.requireReadable(discussion.getRepository(), actorId);
        requireAuthorOrMaintainer(discussion, actorId);
        discussion.setAcceptedReplyId(null);
        discussions.saveAndFlush(discussion);
    }

    @Transactional
    public void addReaction(Long discussionId, DiscussionReactionType type, Long actorId) {
        RepositoryDiscussion discussion = findLocked(discussionId);
        access.requireReadable(discussion.getRepository(), actorId);
        if (reactions.findByDiscussion_IdAndUser_IdAndType(discussionId, actorId, type).isEmpty()) {
            User user = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
            reactions.saveAndFlush(new DiscussionReaction(discussion, user, type));
        }
    }

    @Transactional
    public void removeReaction(Long discussionId, DiscussionReactionType type, Long actorId) {
        RepositoryDiscussion discussion = findLocked(discussionId);
        access.requireReadable(discussion.getRepository(), actorId);
        reactions.deleteByDiscussion_IdAndUser_IdAndType(discussionId, actorId, type);
    }

    DiscussionResponse response(RepositoryDiscussion d) {
        return DiscussionResponse.from(d, oneDiscussionCounts(d.getId()));
    }

    private Map<String, Long> oneDiscussionCounts(Long id) {
        Map<String, Long> values = emptyCounts();
        for (Object[] row : reactions.countsForDiscussions(List.of(id))) values.put(((DiscussionReactionType) row[1]).name(), (Long) row[2]);
        return values;
    }

    private Map<Long, Map<String, Long>> discussionCounts(List<Long> ids) {
        Map<Long, Map<String, Long>> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        for (Long id : ids) result.put(id, emptyCounts());
        for (Object[] row : reactions.countsForDiscussions(ids)) {
            @SuppressWarnings("unchecked") Long id = ((Number) row[0]).longValue();
            result.get(id).put(((DiscussionReactionType) row[1]).name(), ((Number) row[2]).longValue());
        }
        return result;
    }

    static Map<String, Long> emptyCounts() {
        Map<String, Long> values = new java.util.LinkedHashMap<>();
        for (DiscussionReactionType type : DiscussionReactionType.values()) values.put(type.name(), 0L);
        return values;
    }

    private RepositoryDiscussion findLocked(Long id) {
        return discussions.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Discussion not found"));
    }
    private RepositoryDiscussion findDetailed(Long id) {
        return discussions.findDetailedById(id).orElseThrow(() -> new ResourceNotFoundException("Discussion not found"));
    }
    private void requireAuthorOrMaintainer(RepositoryDiscussion d, Long actorId) {
        if (!d.getAuthor().getId().equals(actorId) && !access.hasMaintain(d.getRepository(), actorId))
            throw new ResourceForbiddenException("Only the discussion author or a repository maintainer can perform this action");
    }
    static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw new InvalidRequestException("Page must be non-negative and size must be between 1 and 100");
    }
}
