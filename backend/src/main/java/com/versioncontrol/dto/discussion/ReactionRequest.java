package com.versioncontrol.dto.discussion;

import com.versioncontrol.entity.DiscussionReactionType;
import jakarta.validation.constraints.NotNull;
public record ReactionRequest(@NotNull DiscussionReactionType type) { }
