package com.versioncontrol.dto.pullrequest;

import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAnySetter;

public class UpdatePullRequestRequest {
    @Size(max = 255)
    private String title;
    @Size(max = 16000)
    private String description;
    private boolean titlePresent;
    private boolean descriptionPresent;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.titlePresent = true; this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.descriptionPresent = true; this.description = description; }
    public boolean isTitlePresent() { return titlePresent; }
    public boolean isDescriptionPresent() { return descriptionPresent; }

    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported pull request update field: " + name);
    }
}
