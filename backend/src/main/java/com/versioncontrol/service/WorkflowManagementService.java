package com.versioncontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.versioncontrol.dto.workflow.*;
import com.versioncontrol.entity.*;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class WorkflowManagementService {
    private final WorkflowDefinitionRepository workflows;
    private final WorkflowRunRepository runs;
    private final WorkflowJobRepository jobs;
    private final RepositoryAccessService access;
    private final RepositoryRepository repositories;
    private final BranchRepository branches;
    private final CommitRepository commits;
    private final UserRepository users;
    private final RepositoryActivityService activity;
    private final WorkflowConfigurationValidator configurationValidator;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    public WorkflowManagementService(WorkflowDefinitionRepository workflows, WorkflowRunRepository runs,
            WorkflowJobRepository jobs, RepositoryAccessService access, RepositoryRepository repositories,
            BranchRepository branches, CommitRepository commits, UserRepository users, RepositoryActivityService activity,
            WorkflowConfigurationValidator configurationValidator, ObjectMapper mapper, ApplicationEventPublisher events) {
        this.workflows = workflows; this.runs = runs; this.jobs = jobs; this.access = access; this.repositories = repositories;
        this.branches = branches; this.commits = commits; this.users = users; this.activity = activity;
        this.configurationValidator = configurationValidator; this.mapper = mapper; this.events = events;
    }

    @Transactional
    public WorkflowResponse create(Long repositoryId, CreateWorkflowRequest request, Long userId) {
        Repository repository = access.find(repositoryId);
        access.requireMaintain(repository, userId);
        String name = normalizeName(request.name());
        if (workflows.existsByRepository_IdAndNameIgnoreCase(repositoryId, name))
            throw new RepositoryConflictException("A workflow with this name already exists in the repository");
        var config = configurationValidator.validate(request.configuration());
        User creator = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        WorkflowDefinition workflow;
        try {
            workflow = workflows.saveAndFlush(new WorkflowDefinition(repository, name, cleanDescription(request.description()),
                    request.triggerType(), 1, config.json(), creator));
        } catch (DataIntegrityViolationException collision) {
            throw new RepositoryConflictException("A workflow with this name already exists in the repository");
        }
        activity.record(repository, userId, RepositoryActivityEventType.WORKFLOW_CREATED, "WORKFLOW", workflow.getId(),
                "Created a workflow", "WORKFLOW_CREATED:" + workflow.getId());
        return response(workflow);
    }

    @Transactional(readOnly = true)
    public WorkflowPageResponse list(Long repositoryId, int page, int size, Long userId) {
        Repository repository = access.find(repositoryId); access.requireReadable(repository, userId); validatePage(page, size);
        Page<WorkflowDefinition> result = workflows.findByRepository_IdOrderByCreatedAtDescIdDesc(repositoryId,
                PageRequest.of(page, size, Sort.unsorted()));
        return new WorkflowPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.hasNext(), result.getContent().stream().map(this::response).toList());
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(Long workflowId, Long userId) {
        WorkflowDefinition workflow = findWorkflow(workflowId); access.requireReadable(workflow.getRepository(), userId);
        return response(workflow);
    }

    @Transactional
    public WorkflowResponse update(Long workflowId, UpdateWorkflowRequest request, Long userId) {
        WorkflowDefinition workflow = lockWorkflow(workflowId); access.requireMaintain(workflow.getRepository(), userId);
        if (!request.isNamePresent() && !request.isDescriptionPresent() && !request.isTriggerTypePresent() && !request.isConfigurationPresent())
            throw new InvalidRequestException("At least one workflow field is required");
        if (request.isNamePresent()) {
            String name = normalizeName(request.getName());
            if (!workflow.getName().equalsIgnoreCase(name)
                    && workflows.existsByRepository_IdAndNameIgnoreCase(workflow.getRepository().getId(), name))
                throw new RepositoryConflictException("A workflow with this name already exists in the repository");
            workflow.setName(name);
        }
        if (request.isDescriptionPresent()) workflow.setDescription(cleanDescription(request.getDescription()));
        if (request.isTriggerTypePresent()) {
            if (request.getTriggerType() == null) throw new InvalidRequestException("triggerType cannot be null");
            workflow.setTriggerType(request.getTriggerType());
        }
        if (request.isConfigurationPresent()) {
            var config = configurationValidator.validate(request.getConfiguration());
            workflow.setConfigurationVersion(1); workflow.setConfigurationJson(config.json());
        }
        return response(workflow);
    }

    @Transactional
    public WorkflowResponse setEnabled(Long workflowId, Long userId, boolean enabled) {
        WorkflowDefinition workflow = lockWorkflow(workflowId); access.requireMaintain(workflow.getRepository(), userId);
        workflow.setEnabled(enabled);
        return response(workflow);
    }

    @Transactional
    public void delete(Long workflowId, Long userId) {
        WorkflowDefinition workflow = lockWorkflow(workflowId); access.requireMaintain(workflow.getRepository(), userId);
        workflows.delete(workflow); workflows.flush();
    }

    @Transactional
    public WorkflowRunResponse dispatch(Long workflowId, DispatchWorkflowRequest request, Long userId) {
        WorkflowDefinition workflow = lockWorkflow(workflowId);
        access.requireWrite(workflow.getRepository(), userId);
        if (!workflow.isEnabled()) throw new RepositoryConflictException("Disabled workflows cannot be dispatched");
        if (workflow.getTriggerType() != WorkflowTriggerType.MANUAL)
            throw new RepositoryConflictException("Only MANUAL workflows can be dispatched directly");
        Branch branch = branches.findByRepository_IdAndName(workflow.getRepository().getId(), request.branchName())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        String sha = request.commitSha() == null ? branch.getHeadCommit().getCommitHash() : request.commitSha().toLowerCase();
        Commit commit = commits.findByCommitHash(sha)
                .filter(c -> c.getRepository().getId().equals(workflow.getRepository().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Commit not found in this repository"));
        if (request.commitSha() == null && !commit.getId().equals(branch.getHeadCommit().getId()))
            throw new ResourceNotFoundException("Commit not found in this repository");
        User actor = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        var config = configurationValidator.validate(readConfiguration(workflow.getConfigurationJson()));
        WorkflowRun run = runs.saveAndFlush(new WorkflowRun(workflow, "manual:" + UUID.randomUUID(), WorkflowTriggerType.MANUAL,
                branch.getName(), commit.getCommitHash(), request.simulatedOutcome() == null ? SimulatedWorkflowOutcome.SUCCESS : request.simulatedOutcome(), actor));
        createJobs(run, config.jobs());
        activity.record(workflow.getRepository(), userId, RepositoryActivityEventType.WORKFLOW_RUN_QUEUED, "WORKFLOW_RUN", run.getId(),
                "Queued a workflow run", "WORKFLOW_RUN_QUEUED:" + run.getId());
        return runResponse(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchTriggered(WorkflowDefinition matched, WorkflowTriggerEvent event) {
        WorkflowDefinition workflow = lockWorkflow(matched.getId());
        if (!workflow.isEnabled() || workflow.getTriggerType() != event.triggerType()) return;
        String key = eventKey(event.eventKey());
        if (runs.findByWorkflow_IdAndDispatchKey(workflow.getId(), key).isPresent()) return;
        var config = configurationValidator.validate(readConfiguration(workflow.getConfigurationJson()));
        User actor = event.actorId() == null ? null : users.findById(event.actorId()).orElse(null);
        WorkflowRun run = runs.saveAndFlush(new WorkflowRun(workflow, key, event.triggerType(), event.branchName(), event.commitSha(),
                SimulatedWorkflowOutcome.SUCCESS, actor));
        createJobs(run, config.jobs());
        activity.record(workflow.getRepository(), event.actorId(), RepositoryActivityEventType.WORKFLOW_RUN_QUEUED,
                "WORKFLOW_RUN", run.getId(), "Queued an automatically triggered workflow run", "WORKFLOW_RUN_QUEUED:" + run.getId());
    }

    @Transactional(readOnly = true)
    public WorkflowRunPageResponse listRunsForWorkflow(Long workflowId, int page, int size, Long userId) {
        WorkflowDefinition workflow = findWorkflow(workflowId); access.requireReadable(workflow.getRepository(), userId); validatePage(page, size);
        Page<WorkflowRun> result = runs.findByWorkflow_IdOrderByCreatedAtDescIdDesc(workflowId, PageRequest.of(page, size));
        return runPage(result);
    }

    @Transactional(readOnly = true)
    public WorkflowRunPageResponse listRunsForRepository(Long repositoryId, int page, int size, Long userId) {
        Repository repository = access.find(repositoryId); access.requireReadable(repository, userId); validatePage(page, size);
        Page<WorkflowRun> result = runs.findByWorkflow_Repository_IdOrderByCreatedAtDescIdDesc(repositoryId, PageRequest.of(page, size));
        return runPage(result);
    }

    @Transactional(readOnly = true)
    public WorkflowRunResponse getRun(Long runId, Long userId) {
        WorkflowRun run = findRun(runId); access.requireReadable(run.getWorkflow().getRepository(), userId); return runResponse(run);
    }

    WorkflowDefinition findWorkflow(Long id) { return workflows.findById(id).orElseThrow(() -> new ResourceNotFoundException("Workflow not found")); }
    WorkflowDefinition lockWorkflow(Long id) { return workflows.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Workflow not found")); }
    WorkflowRun findRun(Long id) { return runs.findDetailedById(id).orElseThrow(() -> new ResourceNotFoundException("Workflow run not found")); }

    WorkflowResponse response(WorkflowDefinition w) {
        return new WorkflowResponse(w.getId(), w.getRepository().getId(), w.getName(), w.getDescription(), w.getTriggerType(),
                w.isEnabled(), w.getConfigurationVersion(), readConfiguration(w.getConfigurationJson()),
                w.getCreatedBy() == null ? null : w.getCreatedBy().getId(), w.getCreatedAt(), w.getUpdatedAt());
    }
    WorkflowRunResponse runResponse(WorkflowRun r) {
        return new WorkflowRunResponse(r.getId(), r.getWorkflow().getId(), r.getWorkflow().getName(), r.getWorkflow().getRepository().getId(),
                r.getTriggerType(), r.getStatus(), r.getBranchName(), r.getCommitSha(), r.getTriggeredBy() == null ? null : r.getTriggeredBy().getId(),
                r.getCreatedAt(), r.getStartedAt(), r.getCompletedAt(), true);
    }
    void createJobs(WorkflowRun run, List<WorkflowConfigurationValidator.JobSpec> specs) {
        for (int i = 0; i < specs.size(); i++) jobs.save(new WorkflowJob(run, specs.get(i).name(), i));
        jobs.flush();
    }
    JsonNode readConfiguration(String value) {
        try { return mapper.readTree(value); }
        catch (Exception invalidStoredConfig) { throw new IllegalStateException("Stored workflow configuration is invalid", invalidStoredConfig); }
    }
    private WorkflowRunPageResponse runPage(Page<WorkflowRun> page) {
        return new WorkflowRunPageResponse(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                page.hasNext(), page.getContent().stream().map(this::runResponse).toList());
    }
    private String normalizeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 100) throw new InvalidRequestException("Workflow name must contain 1 to 100 characters");
        return name;
    }
    private String cleanDescription(String value) {
        if (value != null && value.length() > 500) throw new InvalidRequestException("Workflow description is limited to 500 characters");
        return value == null || value.isBlank() ? null : value.trim();
    }
    private void validatePage(int page, int size) {
        if (page < 0 || page > 2_000 || size < 1 || size > 100)
            throw new InvalidRequestException("Page must be between 0 and 2000 and size between 1 and 100");
    }
    private String eventKey(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "event:" + HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
