package com.versioncontrol.dto.discussion;

import jakarta.validation.constraints.NotNull;
public record AcceptedAnswerRequest(@NotNull Long replyId) { }
