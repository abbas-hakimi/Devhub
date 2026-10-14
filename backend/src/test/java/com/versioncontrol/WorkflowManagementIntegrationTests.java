package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.entity.WorkflowTriggerType;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.WorkflowDefinitionRepository;
import com.versioncontrol.repository.WorkflowJobLogRepository;
import com.versioncontrol.repository.WorkflowJobRepository;
import com.versioncontrol.repository.WorkflowRunRepository;
import com.versioncontrol.service.WorkflowManagementService;
import com.versioncontrol.service.WorkflowRunExecutionService;
import com.versioncontrol.service.WorkflowSimulationScheduler;
import com.versioncontrol.service.WorkflowTriggerEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"workflow.simulation.fixed-delay-ms=60000", "workflow.simulation.initial-delay-ms=60000"})
@AutoConfigureMockMvc
class WorkflowManagementIntegrationTests {
    private static final AtomicLong IDS = new AtomicLong();
    private static final String CONFIG = "{\"version\":1,\"jobs\":[{\"name\":\"Build\",\"steps\":[\"CHECKOUT\",\"BUILD\",\"TEST\"]},{\"name\":\"Package\",\"steps\":[\"PACKAGE\"]}]}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired WorkflowDefinitionRepository workflowRows;
    @Autowired WorkflowRunRepository runRows;
    @Autowired WorkflowJobRepository jobRows;
    @Autowired WorkflowJobLogRepository logRows;
    @Autowired BranchRepository branchRows;
    @Autowired CommitRepository commitRows;
    @Autowired RepositoryRepository repositoryRows;
    @Autowired WorkflowRunExecutionService execution;
    @Autowired WorkflowSimulationScheduler scheduler;
    @Autowired WorkflowManagementService management;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEventPublisher events;

    @Test
    void workflowCrudValidatesConfigurationAndAllowsPublicAnonymousReads() throws Exception {
        Account owner = register("workflowcrud");
        long repository = createRepository(owner, "PUBLIC");
        mvc.perform(get("/api/repositories/" + repository + "/workflows"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/repositories/" + repository + "/workflows").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(workflowBody("Invalid", "MANUAL",
                        "{\"version\":1,\"jobs\":[{\"name\":\"Run arbitrary code\",\"steps\":[\"SHELL\"]}]}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/repositories/" + repository + "/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(workflowBody("Anonymous", "MANUAL", CONFIG))).andExpect(status().isUnauthorized());
        long workflowId = createWorkflow(owner, repository, "Build pipeline", "MANUAL");
        mvc.perform(get("/api/workflows/" + workflowId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration.version").value(1)).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/repositories/" + repository + "/workflows")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(patch("/api/workflows/" + workflowId).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed pipeline\",\"description\":\"updated\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed pipeline"));
        mvc.perform(post("/api/workflows/" + workflowId + "/disable").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/workflows/" + workflowId + "/dispatch").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchName\":\"main\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/workflows/" + workflowId + "/enable").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(delete("/api/workflows/" + workflowId).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/workflows/" + workflowId)).andExpect(status().isNotFound());
    }

    @Test
    void privateVisibilityAndReadWriteMaintainPermissionsAreEnforced() throws Exception {
        Account owner = register("workflowowner");
        Account reader = register("workflowreader");
        Account writer = register("workflowwriter");
        long repository = createRepository(owner, "PRIVATE");
        String initialHash = initialize(owner, repository);
        long workflowId = createWorkflow(owner, repository, "Private workflow", "MANUAL");
        mvc.perform(get("/api/workflows/" + workflowId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/workflows/" + workflowId).header("Authorization", bearer(reader))).andExpect(status().isNotFound());
        invite(owner, reader, repository, "READ");
        invite(owner, writer, repository, "WRITE");
        mvc.perform(get("/api/workflows/" + workflowId).header("Authorization", bearer(reader))).andExpect(status().isOk());
        mvc.perform(patch("/api/workflows/" + workflowId).header("Authorization", bearer(reader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Reader edit\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/workflows/" + workflowId + "/dispatch").header("Authorization", bearer(reader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchName\":\"main\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/repositories/" + repository + "/workflows").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content(workflowBody("Writer create", "MANUAL", CONFIG))).andExpect(status().isForbidden());
        mvc.perform(post("/api/workflows/" + workflowId + "/dispatch").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchName\":\"main\",\"commitSha\":\"" + initialHash + "\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/workflow-runs/999999999/jobs").header("Authorization", bearer(reader))).andExpect(status().isNotFound());
    }

    @Test
    void simulatorAdvancesSuccessAndFailureAndCancellationIsTerminal() throws Exception {
        Account owner = register("workflowsim");
        long repository = createRepository(owner, "PUBLIC");
        initialize(owner, repository);
        long workflow = createWorkflow(owner, repository, "Simulation", "MANUAL");
        long successRun = dispatch(owner, workflow, "SUCCESS");
        mvc.perform(get("/api/workflow-runs/" + successRun).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED")).andExpect(jsonPath("$.simulated").value(true));
        for (int i = 0; i < 5; i++) execution.advanceOne(successRun);
        mvc.perform(get("/api/workflow-runs/" + successRun).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        String jobsJson = mvc.perform(get("/api/workflow-runs/" + successRun + "/jobs").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        long firstJob = ((Number) JsonPath.read(jobsJson, "$[0].id")).longValue();
        mvc.perform(get("/api/workflow-jobs/" + firstJob + "/logs").header("Authorization", bearer(owner)).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));

        long failureRun = dispatch(owner, workflow, "FAILURE");
        for (int i = 0; i < 6; i++) execution.advanceOne(failureRun);
        mvc.perform(get("/api/workflow-runs/" + failureRun).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILURE"));
        long cancelledRun = dispatch(owner, workflow, "SUCCESS");
        mvc.perform(post("/api/workflow-runs/" + cancelledRun + "/cancel").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        execution.advanceOne(cancelledRun);
        mvc.perform(get("/api/workflow-runs/" + cancelledRun).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/workflow-runs/" + cancelledRun + "/cancel").header("Authorization", bearer(owner)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/repositories/" + repository + "/workflow-runs").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void concurrentDispatchCreatesDistinctRunsAndCancellationSerializesWithAdvancement() throws Exception {
        Account owner = register("workflowrace");
        long repository = createRepository(owner, "PUBLIC");
        initialize(owner, repository);
        long workflow = createWorkflow(owner, repository, "Concurrent", "MANUAL");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> dispatchConcurrently(owner, workflow, ready, start));
            var b = pool.submit(() -> dispatchConcurrently(owner, workflow, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            long runA = a.get(10, TimeUnit.SECONDS); long runB = b.get(10, TimeUnit.SECONDS);
            assertThat(runA).isNotEqualTo(runB);
            assertThat(runRows.countByWorkflow_Id(workflow)).isEqualTo(2);
            var cancelReady = new CountDownLatch(2); var cancelStart = new CountDownLatch(1);
            var cancel = pool.submit(() -> {
                cancelReady.countDown(); cancelStart.await();
                return mvc.perform(post("/api/workflow-runs/" + runA + "/cancel").header("Authorization", bearer(owner)))
                        .andReturn().getResponse().getStatus();
            });
            var advance = pool.submit(() -> { cancelReady.countDown(); cancelStart.await(); execution.advanceOne(runA); return true; });
            assertThat(cancelReady.await(5, TimeUnit.SECONDS)).isTrue(); cancelStart.countDown();
            assertThat(cancel.get(10, TimeUnit.SECONDS)).isEqualTo(200); assertThat(advance.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(runRows.findDetailedById(runA).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
        } finally { pool.shutdownNow(); }
    }

    @Test
    void schedulerProcessesQueuedRunsUsingTheSafeSimulator() throws Exception {
        Account owner = register("workflowscheduler");
        long repository = createRepository(owner, "PUBLIC");
        initialize(owner, repository);
        long workflow = createWorkflow(owner, repository, "Scheduled simulation", "MANUAL");
        long run = dispatch(owner, workflow, "SUCCESS");
        for (int i = 0; i < 8; i++) scheduler.tick();
        mvc.perform(get("/api/workflow-runs/" + run).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void pushAndPullRequestTriggersRunAfterCommitAndDeduplicate() throws Exception {
        Account owner = register("workflowtrigger");
        long repository = createRepository(owner, "PUBLIC");
        String initialHash = initialize(owner, repository);
        long pushWorkflow = createWorkflow(owner, repository, "On push", "PUSH");
        String commit = mvc.perform(post("/api/repositories/" + repository + "/commits").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"main\",\"expectedHeadHash\":\"" + initialHash
                        + "\",\"message\":\"trigger push\",\"changes\":[{\"path\":\"README.md\",\"content\":\"synthetic\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String commitSha = JsonPath.read(commit, "$.commitHash");
        assertThat(runRows.countByWorkflow_Id(pushWorkflow)).isEqualTo(1);
        var main = mvc.perform(get("/api/repositories/" + repository + "/branches").header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long branchId = ((Number) JsonPath.read(main, "$[0].id")).longValue();
        WorkflowTriggerEvent repeated = new WorkflowTriggerEvent(repository, WorkflowTriggerType.PUSH, owner.id(), "main", commitSha,
                "push:" + repository + ":" + branchId + ":" + commitSha);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> { events.publishEvent(repeated); events.publishEvent(repeated); });
        assertThat(runRows.countByWorkflow_Id(pushWorkflow)).isEqualTo(1);

        long prWorkflow = createWorkflow(owner, repository, "On pull request", "PULL_REQUEST");
        mvc.perform(post("/api/repositories/" + repository + "/branches").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"feature\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/repositories/" + repository + "/commits").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"feature\",\"message\":\"feature commit\",\"changes\":[{\"path\":\"feature.txt\",\"content\":\"feature\"}]}"))
                .andExpect(status().isCreated());
        assertThat(runRows.countByWorkflow_Id(pushWorkflow)).isEqualTo(2);
        mvc.perform(post("/api/repositories/" + repository + "/pull-requests").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Trigger PR workflow\",\"sourceBranch\":\"feature\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isCreated());
        assertThat(runRows.countByWorkflow_Id(prWorkflow)).isEqualTo(1);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> tx.executeWithoutResult(s -> {
            events.publishEvent(new WorkflowTriggerEvent(repository, WorkflowTriggerType.PUSH, owner.id(), "main", commitSha,
                    "rollback-trigger"));
            throw new IllegalStateException("rollback origin transaction");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(runRows.countByWorkflow_Id(pushWorkflow)).isEqualTo(2);
    }

    @Test
    void workflowRunDependenciesRollbackAndCascadeOnRepositoryDeletion() throws Exception {
        Account owner = register("workflowcleanup");
        long repository = createRepository(owner, "PRIVATE");
        long workflow = createWorkflow(owner, repository, "Cleanup workflow", "PUSH");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> events.publishEvent(new WorkflowTriggerEvent(repository, WorkflowTriggerType.PUSH,
                owner.id(), "main", null, "cleanup-event")));
        assertThat(runRows.countByWorkflow_Id(workflow)).isEqualTo(1);
        long runId = runRows.findByWorkflow_IdOrderByCreatedAtDescIdDesc(workflow,
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0).getId();
        var runJobs = jobRows.findByRun_IdOrderBySequenceNumberAsc(runId);
        long jobsBefore = runJobs.size();
        assertThat(jobsBefore).isEqualTo(2);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> tx.executeWithoutResult(s -> {
            management.delete(workflow, owner.id());
            throw new IllegalStateException("simulate failure before deletion commit");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(workflowRows.existsById(workflow)).isTrue();
        assertThat(runRows.countByWorkflow_Id(workflow)).isEqualTo(1);
        assertThat(jobRows.countByRun_Id(runId)).isEqualTo(jobsBefore);
        mvc.perform(delete("/api/repositories/" + repository).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        assertThat(workflowRows.existsById(workflow)).isFalse();
        assertThat(runRows.existsById(runId)).isFalse();
        assertThat(jobRows.countByRun_Id(runId)).isZero();
        runJobs.forEach(job -> assertThat(logRows.countByJob_Id(job.getId())).isZero());
    }

    @Test
    void initializedRepositoryCleanupRemovesWorkflowDataButPreservesGitHistoryGuard() throws Exception {
        Account owner = register("workflowgitcleanup");
        Account collaborator = register("workflowgitcollab");
        long repository = createRepository(owner, "PRIVATE");
        initialize(owner, repository);
        long workflow = createWorkflow(owner, repository, "Disposable workflow", "MANUAL");
        invite(owner, collaborator, repository, "READ");

        long successRun = dispatch(owner, workflow, "SUCCESS");
        for (int i = 0; i < 5; i++) execution.advanceOne(successRun);
        long failureRun = dispatch(owner, workflow, "FAILURE");
        for (int i = 0; i < 6; i++) execution.advanceOne(failureRun);

        var createdJobs = java.util.stream.Stream.concat(
                jobRows.findByRun_IdOrderBySequenceNumberAsc(successRun).stream(),
                jobRows.findByRun_IdOrderBySequenceNumberAsc(failureRun).stream()).toList();
        assertThat(createdJobs).hasSize(4);
        assertThat(createdJobs).allSatisfy(job -> assertThat(logRows.countByJob_Id(job.getId())).isPositive());

        mvc.perform(delete("/api/workflows/" + workflow).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(workflowRows.existsById(workflow)).isFalse();
        assertThat(runRows.countByWorkflow_Id(workflow)).isZero();
        createdJobs.forEach(job -> {
            assertThat(jobRows.existsById(job.getId())).isFalse();
            assertThat(logRows.countByJob_Id(job.getId())).isZero();
        });

        mvc.perform(delete("/api/repositories/" + repository + "/collaborators/" + collaborator.id())
                .header("Authorization", bearer(owner))).andExpect(status().isNoContent());

        // Git history has no delete API and is protected by RESTRICT foreign keys.
        // Removing the workflow and collaborator must not silently erase it to make repository cleanup pass.
        mvc.perform(delete("/api/repositories/" + repository).header("Authorization", bearer(owner)))
                .andExpect(status().isConflict());
        assertThat(repositoryRows.existsById(repository)).isTrue();
        assertThat(branchRows.countByRepository_IdAndName(repository, "main")).isEqualTo(1);
        assertThat(commitRows.countByRepository_Id(repository)).isEqualTo(1);
        assertThat(workflowRows.existsById(workflow)).isFalse();
        assertThat(runRows.countByWorkflow_Id(workflow)).isZero();
    }

    private long dispatch(Account owner, long workflowId, String outcome) throws Exception {
        String json = mvc.perform(post("/api/workflows/" + workflowId + "/dispatch").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchName\":\"main\",\"simulatedOutcome\":\"" + outcome + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
    private long dispatchConcurrently(Account owner, long workflow, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); start.await();
        String json = mvc.perform(post("/api/workflows/" + workflow + "/dispatch").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchName\":\"main\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
    private long createWorkflow(Account owner, long repository, String name, String trigger) throws Exception {
        String json = mvc.perform(post("/api/repositories/" + repository + "/workflows").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(workflowBody(name, trigger, CONFIG)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
    private String workflowBody(String name, String trigger, String config) {
        return "{\"name\":\"" + name + "\",\"triggerType\":\"" + trigger + "\",\"configuration\":" + config + "}";
    }
    private long createRepository(Account owner, String visibility) throws Exception {
        String name = "workflow-" + IDS.incrementAndGet() + "-" + System.nanoTime();
        String json = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
    private String initialize(Account owner, long repository) throws Exception {
        String json = mvc.perform(post("/api/repositories/" + repository + "/git/init").header("Authorization", bearer(owner)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.commitHash");
    }
    private void invite(Account owner, Account invitee, long repository, String role) throws Exception {
        String json = mvc.perform(post("/api/repositories/" + repository + "/collaborators/invitations").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + invitee.username() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long invitation = ((Number) JsonPath.read(json, "$.invitationId")).longValue();
        mvc.perform(post("/api/collaboration-invitations/" + invitation + "/accept").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk());
    }
    private Account register(String prefix) throws Exception {
        String username = prefix + IDS.incrementAndGet() + System.nanoTime();
        String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode root = mapper.readTree(json);
        return new Account(root.path("user").path("id").longValue(), username, root.path("accessToken").asText());
    }
    private String bearer(Account account) { return "Bearer " + account.token(); }
    private record Account(Long id, String username, String token) { }
}
