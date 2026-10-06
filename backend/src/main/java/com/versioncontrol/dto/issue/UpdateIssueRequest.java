package com.versioncontrol.dto.issue;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.versioncontrol.entity.IssueStatus;
import jakarta.validation.constraints.Size;

public class UpdateIssueRequest {
    @Size(max = 255)
    private String title;
    @Size(max = 16000)
    private String description;
    private IssueStatus status;
    private Long assigneeId;
    private boolean assigneeIdSpecified;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public IssueStatus getStatus() { return status; }
    public void setStatus(IssueStatus status) { this.status = status; }
    public Long getAssigneeId() { return assigneeId; }
    @JsonSetter("assigneeId")
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; this.assigneeIdSpecified = true; }
    public boolean isAssigneeIdSpecified() { return assigneeIdSpecified; }
}
