package com.versioncontrol.dto.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateReviewCommentRequest(@NotBlank @Size(max = 10_000) String body) { }
