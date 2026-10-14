package com.versioncontrol.dto.project;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
public record ColumnOrderRequest(@NotEmpty @Size(max = 100) List<Long> columnIds) { }
