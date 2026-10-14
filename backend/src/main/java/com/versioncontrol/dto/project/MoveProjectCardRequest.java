package com.versioncontrol.dto.project;

import jakarta.validation.constraints.NotNull;
public record MoveProjectCardRequest(@NotNull Long columnId, Integer position) { }
