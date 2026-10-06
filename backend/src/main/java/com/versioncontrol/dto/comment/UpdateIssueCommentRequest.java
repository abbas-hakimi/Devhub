package com.versioncontrol.dto.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateIssueCommentRequest(@NotBlank @Size(max = 16000) String body) { }
