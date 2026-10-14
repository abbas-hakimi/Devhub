package com.versioncontrol.dto.discussion;

import java.time.LocalDateTime;
import java.util.Map;
import com.versioncontrol.entity.DiscussionReply;
public record DiscussionReplyResponse(Long id, Long discussionId, Long parentReplyId, String body,
        DiscussionResponse.SafeUser author, LocalDateTime createdAt, LocalDateTime updatedAt, Map<String, Long> reactions) {
    public static DiscussionReplyResponse from(DiscussionReply r, Map<String, Long> reactions) {
        return new DiscussionReplyResponse(r.getId(), r.getDiscussion().getId(), r.getParent() == null ? null : r.getParent().getId(),
                r.getBody(), DiscussionResponse.SafeUser.from(r.getAuthor()), r.getCreatedAt(), r.getUpdatedAt(), Map.copyOf(reactions));
    }
}
