package com.versioncontrol.dto.project;

import java.util.List;
public record ProjectBoardResponse(ProjectResponse project, List<ColumnBoard> columns) {
    public record ColumnBoard(ProjectColumnResponse column, List<ProjectCardResponse> cards) { }
}
