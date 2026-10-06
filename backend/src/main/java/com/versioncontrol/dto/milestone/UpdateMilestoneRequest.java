package com.versioncontrol.dto.milestone;

import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.versioncontrol.entity.MilestoneState;
import jakarta.validation.constraints.Size;

public class UpdateMilestoneRequest {
    @Size(max = 100)
    private String title;
    @Size(max = 16000)
    private String description;
    private LocalDate dueDate;
    private MilestoneState state;
    private boolean titleSpecified;
    private boolean descriptionSpecified;
    private boolean dueDateSpecified;
    private boolean stateSpecified;

    public String getTitle() { return title; }
    @JsonSetter("title")
    public void setTitle(String title) { this.title = title; this.titleSpecified = true; }
    public String getDescription() { return description; }
    @JsonSetter("description")
    public void setDescription(String description) { this.description = description; this.descriptionSpecified = true; }
    public LocalDate getDueDate() { return dueDate; }
    @JsonSetter("dueDate")
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; this.dueDateSpecified = true; }
    public MilestoneState getState() { return state; }
    @JsonSetter("state")
    public void setState(MilestoneState state) { this.state = state; this.stateSpecified = true; }
    public boolean isTitleSpecified() { return titleSpecified; }
    public boolean isDescriptionSpecified() { return descriptionSpecified; }
    public boolean isDueDateSpecified() { return dueDateSpecified; }
    public boolean isStateSpecified() { return stateSpecified; }
}
