package com.versioncontrol.dto.discussion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record UpdateReplyRequest(@NotBlank @Size(max = 16000) String body) { }
