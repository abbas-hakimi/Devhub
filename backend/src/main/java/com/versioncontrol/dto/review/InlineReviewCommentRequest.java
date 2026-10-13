package com.versioncontrol.dto.review;

import com.versioncontrol.entity.ReviewCommentSide;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record InlineReviewCommentRequest(
        @NotBlank @Size(max = 1024) String path,
        @NotNull @Positive Integer lineNumber,
        @NotNull ReviewCommentSide side,
        @NotBlank @Size(max = 10_000) String body) { }
