package com.versioncontrol.dto.discussion;

import java.time.LocalDateTime;
import java.util.Map;
import com.versioncontrol.entity.DiscussionCategory;
import com.versioncontrol.entity.DiscussionState;
import com.versioncontrol.entity.RepositoryDiscussion;

public record DiscussionResponse(Long id, Long repositoryId, Long number, DiscussionCategory category, String title,
        String body, SafeUser author, DiscussionState state, LocalDateTime createdAt, LocalDateTime updatedAt,
        LocalDateTime closedAt, Long acceptedAnswerReplyId, Map<String, Long> reactions) {
    public static DiscussionResponse from(RepositoryDiscussion d, Map<String, Long> reactions) {
        return new DiscussionResponse(d.getId(), d.getRepository().getId(), d.getNumber(), d.getCategory(), d.getTitle(),
                d.getBody(), SafeUser.from(d.getAuthor()), d.getState(), d.getCreatedAt(), d.getUpdatedAt(), d.getClosedAt(),
                d.getAcceptedReplyId(), Map.copyOf(reactions));
    }
    public record SafeUser(Long id, String username, String displayName) {
        public static SafeUser from(com.versioncontrol.entity.User user) { return new SafeUser(user.getId(), user.getUsername(), user.getDisplayName()); }
    }
}
