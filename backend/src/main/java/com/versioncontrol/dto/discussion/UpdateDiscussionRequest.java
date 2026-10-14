package com.versioncontrol.dto.discussion;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

public class UpdateDiscussionRequest {
    private String title;
    private String body;
    private boolean titleSpecified;
    private boolean bodySpecified;
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public boolean isTitleSpecified() { return titleSpecified; }
    public boolean isBodySpecified() { return bodySpecified; }
    @JsonSetter("title") public void setTitle(String title) { this.title = title; this.titleSpecified = true; }
    @JsonSetter("body") public void setBody(String body) { this.body = body; this.bodySpecified = true; }
    @JsonAnySetter public void rejectUnsupportedField(String name, Object value) { throw new IllegalArgumentException("Unsupported discussion update field: " + name); }
}
