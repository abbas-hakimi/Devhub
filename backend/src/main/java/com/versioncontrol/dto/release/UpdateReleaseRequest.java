package com.versioncontrol.dto.release;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Size;

public class UpdateReleaseRequest {
    @Size(max = 255)
    private String title;
    @Size(max = 16000)
    private String description;
    private Boolean draft;
    private Boolean prerelease;
    private boolean titleSpecified;
    private boolean descriptionSpecified;
    private boolean draftSpecified;
    private boolean prereleaseSpecified;

    public String getTitle() { return title; }
    @JsonSetter("title")
    public void setTitle(String title) { this.title = title; this.titleSpecified = true; }
    public String getDescription() { return description; }
    @JsonSetter("description")
    public void setDescription(String description) { this.description = description; this.descriptionSpecified = true; }
    public Boolean getDraft() { return draft; }
    @JsonSetter("draft")
    public void setDraft(Boolean draft) { this.draft = draft; this.draftSpecified = true; }
    public Boolean getPrerelease() { return prerelease; }
    @JsonSetter("prerelease")
    public void setPrerelease(Boolean prerelease) { this.prerelease = prerelease; this.prereleaseSpecified = true; }
    public boolean isTitleSpecified() { return titleSpecified; }
    public boolean isDescriptionSpecified() { return descriptionSpecified; }
    public boolean isDraftSpecified() { return draftSpecified; }
    public boolean isPrereleaseSpecified() { return prereleaseSpecified; }

    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported release update field: " + name);
    }
}
