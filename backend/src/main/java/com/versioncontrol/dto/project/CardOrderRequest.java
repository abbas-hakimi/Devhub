package com.versioncontrol.dto.project;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
public record CardOrderRequest(@NotNull Long columnId, @NotEmpty @Size(max = 10000) List<Long> cardIds) { }
