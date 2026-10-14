package com.versioncontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.versioncontrol.exception.InvalidRequestException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WorkflowConfigurationValidator {
    private static final int MAX_JSON_CHARS = 16_384;
    private static final Set<String> ALLOWED_STEPS = Set.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "DEPLOY");
    private final ObjectMapper mapper;

    public WorkflowConfigurationValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public ValidatedConfiguration validate(JsonNode input) {
        if (input == null || !input.isObject() || !input.has("version") || !input.get("version").canConvertToInt()
                || input.get("version").intValue() != 1 || !input.has("jobs") || !input.get("jobs").isArray()) {
            throw new InvalidRequestException("configuration must be an object with version 1 and a jobs array");
        }
        requireOnlyFields(input, Set.of("version", "jobs"), "configuration");
        JsonNode jobsNode = input.get("jobs");
        if (jobsNode.size() < 1 || jobsNode.size() > 10) throw new InvalidRequestException("configuration must define 1 to 10 jobs");
        ArrayNode normalizedJobs = mapper.createArrayNode();
        List<JobSpec> jobs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode item : jobsNode) {
            if (!item.isObject() || !item.has("name") || !item.get("name").isTextual()
                    || !item.has("steps") || !item.get("steps").isArray()) {
                throw new InvalidRequestException("Each job requires a name and steps array");
            }
            requireOnlyFields(item, Set.of("name", "steps"), "job");
            String name = item.get("name").textValue().trim();
            if (name.isEmpty() || name.length() > 100) throw new InvalidRequestException("Job names must contain 1 to 100 characters");
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw new InvalidRequestException("Job names must be unique within a workflow");
            JsonNode stepsNode = item.get("steps");
            if (stepsNode.size() < 1 || stepsNode.size() > 20) throw new InvalidRequestException("Each job must define 1 to 20 simulated steps");
            ArrayNode normalizedSteps = mapper.createArrayNode();
            List<String> steps = new ArrayList<>();
            for (JsonNode stepNode : stepsNode) {
                if (!stepNode.isTextual()) throw new InvalidRequestException("Workflow steps must be strings from the supported allowlist");
                String step = stepNode.textValue().trim().toUpperCase(Locale.ROOT);
                if (!ALLOWED_STEPS.contains(step)) throw new InvalidRequestException("Unsupported simulated step: " + step);
                normalizedSteps.add(step); steps.add(step);
            }
            ObjectNode normalizedJob = mapper.createObjectNode();
            normalizedJob.put("name", name); normalizedJob.set("steps", normalizedSteps); normalizedJobs.add(normalizedJob);
            jobs.add(new JobSpec(name, List.copyOf(steps)));
        }
        ObjectNode normalized = mapper.createObjectNode();
        normalized.put("version", 1); normalized.set("jobs", normalizedJobs);
        try {
            String json = mapper.writeValueAsString(normalized);
            if (json.length() > MAX_JSON_CHARS) throw new InvalidRequestException("Workflow configuration is limited to 16384 characters");
            return new ValidatedConfiguration(json, List.copyOf(jobs));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new InvalidRequestException("Workflow configuration could not be normalized");
        }
    }

    private void requireOnlyFields(JsonNode node, Set<String> allowed, String label) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new InvalidRequestException("Unsupported " + label + " field: " + field);
        });
    }

    public record JobSpec(String name, List<String> steps) { }
    public record ValidatedConfiguration(String json, List<JobSpec> jobs) { }
}
