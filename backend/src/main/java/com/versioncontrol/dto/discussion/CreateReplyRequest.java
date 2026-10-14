package com.versioncontrol.dto.discussion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record CreateReplyRequest(@NotBlank @Size(max = 16000) String body, Long parentReplyId) { }
