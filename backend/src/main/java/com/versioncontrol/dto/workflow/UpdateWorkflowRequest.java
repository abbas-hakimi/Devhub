package com.versioncontrol.dto.workflow;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.versioncontrol.entity.WorkflowTriggerType;

public class UpdateWorkflowRequest {
    private String name;
    private String description;
    private WorkflowTriggerType triggerType;
    private JsonNode configuration;
    private boolean namePresent;
    private boolean descriptionPresent;
    private boolean triggerTypePresent;
    private boolean configurationPresent;
    @JsonSetter("name") public void setName(String value) { name = value; namePresent = true; }
    @JsonSetter("description") public void setDescription(String value) { description = value; descriptionPresent = true; }
    @JsonSetter("triggerType") public void setTriggerType(WorkflowTriggerType value) { triggerType = value; triggerTypePresent = true; }
    @JsonSetter("configuration") public void setConfiguration(JsonNode value) { configuration = value; configurationPresent = true; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public WorkflowTriggerType getTriggerType() { return triggerType; }
    public JsonNode getConfiguration() { return configuration; }
    public boolean isNamePresent() { return namePresent; }
    public boolean isDescriptionPresent() { return descriptionPresent; }
    public boolean isTriggerTypePresent() { return triggerTypePresent; }
    public boolean isConfigurationPresent() { return configurationPresent; }
}
