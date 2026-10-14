package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.versioncontrol.dto.project.CreateProjectRequest;
import com.versioncontrol.entity.ProjectScope;
import com.versioncontrol.repository.ProjectRepository;
import com.versioncontrol.repository.ProjectCardRepository;
import com.versioncontrol.repository.ProjectColumnRepository;
import com.versioncontrol.repository.RepositoryActivityRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.UserRepository;
import com.versioncontrol.repository.IssueRepository;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.TreeRepository;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.Tree;
import com.versioncontrol.service.ProjectManagementService;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectManagementIntegrationTests {
    private static final AtomicLong IDS = new AtomicLong();
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projectRows;
    @Autowired ProjectCardRepository projectCardRows;
    @Autowired ProjectColumnRepository projectColumnRows;
    @Autowired IssueRepository issueRows;
    @Autowired RepositoryRepository repositoryRows;
    @Autowired RepositoryActivityRepository activities;
    @Autowired UserRepository userRows;
    @Autowired BranchRepository branchRows;
    @Autowired CommitRepository commitRows;
    @Autowired TreeRepository treeRows;
    @Autowired PullRequestRepository pullRequestRows;
    @Autowired ProjectManagementService projectService;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void personalProjectsArePrivateAndHaveDefaultColumnsAndBoardCrudWorks() throws Exception {
        Account owner = register("projectowner"); Account other = register("projectother");
        long projectId = createProject(owner, "PERSONAL", null, "Personal board");
        mvc.perform(get("/api/projects/" + projectId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PRIVATE"));
        mvc.perform(get("/api/projects/" + projectId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + projectId).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"PERSONAL\",\"name\":\"No token\"}"))
                .andExpect(status().isUnauthorized());
        String columns = mvc.perform(get("/api/projects/" + projectId + "/columns").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Todo"))
                .andExpect(jsonPath("$[1].name").value("In Progress")).andExpect(jsonPath("$[2].name").value("In Review"))
                .andExpect(jsonPath("$[3].name").value("Done")).andReturn().getResponse().getContentAsString();
        long todo = ((Number) JsonPath.read(columns, "$[0].id")).longValue();
        long doing = ((Number) JsonPath.read(columns, "$[1].id")).longValue();
        long otherProject = createProject(owner, "PERSONAL", null, "Other board");
        String otherColumns = mvc.perform(get("/api/projects/" + otherProject + "/columns").header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long foreignColumn = ((Number) JsonPath.read(otherColumns, "$[0].id")).longValue();
        mvc.perform(post("/api/projects/" + projectId + "/cards").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + foreignColumn + ",\"cardType\":\"NOTE\",\"title\":\"Cross project\"}"))
                .andExpect(status().isBadRequest());
        long cardA = createNote(owner, projectId, todo, "First task");
        long cardB = createNote(owner, projectId, todo, "Second task");
        mvc.perform(put("/api/projects/" + projectId + "/cards/order").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"columnId\":" + todo + ",\"cardIds\":[" + cardB + "," + cardA + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(cardB)).andExpect(jsonPath("$[0].position").value(0));
        mvc.perform(post("/api/project-cards/" + cardA + "/move").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"columnId\":" + doing + ",\"position\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.columnId").value(doing)).andExpect(jsonPath("$.position").value(0));
        mvc.perform(get("/api/projects/" + projectId + "/board").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.columns[1].cards[0].id").value(cardA));
        mvc.perform(delete("/api/project-columns/" + todo).header("Authorization", bearer(owner))).andExpect(status().isConflict());
        mvc.perform(get("/api/projects/" + projectId + "/board").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.columns[0].cards[0].id").value(cardB));
        String emptyColumn = mvc.perform(post("/api/projects/" + projectId + "/columns").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Empty\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long emptyColumnId = ((Number) JsonPath.read(emptyColumn, "$.id")).longValue();
        mvc.perform(delete("/api/project-columns/" + emptyColumnId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/projects/" + projectId + "/archive").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(post("/api/projects/" + projectId + "/cards").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + todo + ",\"cardType\":\"NOTE\",\"title\":\"No write\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/projects/" + projectId + "/restore").header("Authorization", bearer(owner))).andExpect(status().isOk());
        mvc.perform(get("/api/projects").header("Authorization", bearer(other)).param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test
    void repositoryRolesAndLinkedIssueBoundariesAreEnforced() throws Exception {
        Account owner = register("projectrepoowner"); Account writer = register("projectwriter"); Account reader = register("projectreader");
        long repo = createRepository(owner, "PUBLIC"); invite(owner, writer, repo, "WRITE"); invite(owner, reader, repo, "READ");
        long project = createProject(owner, "REPOSITORY", repo, "Repository board");
        String cols = mvc.perform(get("/api/projects/" + project + "/columns")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long todo = ((Number) JsonPath.read(cols, "$[0].id")).longValue();
        mvc.perform(post("/api/projects/" + project + "/columns").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Blocked\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/projects/" + project + "/cards").header("Authorization", bearer(reader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"columnId\":" + todo + ",\"cardType\":\"NOTE\",\"title\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/projects/" + project + "/cards").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"columnId\":" + todo + ",\"cardType\":\"NOTE\",\"title\":\"Allowed\"}"))
                .andExpect(status().isCreated());
        String issueJson = mvc.perform(post("/api/repositories/" + repo + "/issues").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Linked issue\",\"description\":\"Card body\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long issueId = ((Number) JsonPath.read(issueJson, "$.id")).longValue();
        mvc.perform(post("/api/projects/" + project + "/cards").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + todo + ",\"cardType\":\"ISSUE\",\"issueId\":" + issueId + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Linked issue"));
        mvc.perform(post("/api/projects/" + project + "/cards").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + todo + ",\"cardType\":\"ISSUE\",\"issueId\":" + issueId + "}"))
                .andExpect(status().isConflict());
        long otherRepo = createRepository(owner, "PUBLIC");
        String otherIssue = mvc.perform(post("/api/repositories/" + otherRepo + "/issues").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Other issue\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/projects/" + project + "/cards").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + todo + ",\"cardType\":\"ISSUE\",\"issueId\":" + JsonPath.read(otherIssue, "$.id") + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/" + project + "/board")).andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + project + "/board").header("Authorization", bearer(reader)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/projects/" + project).header("Authorization", bearer(writer))).andExpect(status().isForbidden());
    }

    @Test
    void deletingProjectWithCardsRemovesOnlyBoardDataAndLeavesLinkedResources() throws Exception {
        Account owner = register("projectdeleteowner");
        long repositoryId = createRepository(owner, "PUBLIC");
        var repository = repositoryRows.findById(repositoryId).orElseThrow();
        var author = userRows.findById(owner.id()).orElseThrow();
        String issueJson = mvc.perform(post("/api/repositories/" + repositoryId + "/issues")
                .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Keep this issue\",\"description\":\"Independent resource\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long issueId = ((Number) JsonPath.read(issueJson, "$.id")).longValue();
        Tree tree = treeRows.saveAndFlush(new Tree(hash()));
        Commit base = commitRows.saveAndFlush(new Commit(repository, hash(), "Base", author, tree, null));
        Commit head = commitRows.saveAndFlush(new Commit(repository, hash(), "Feature", author, tree, base));
        Branch main = branchRows.saveAndFlush(new Branch(repository, "main", base));
        Branch feature = branchRows.saveAndFlush(new Branch(repository, "feature", head));
        PullRequest pr = pullRequestRows.saveAndFlush(new PullRequest(repository, 1, "Keep this PR", null, author,
                feature, main, head, base));
        long pullRequestId = pr.getId();

        long projectId = createProject(owner, "REPOSITORY", repositoryId, "Delete board with linked rows");
        String columnJson = mvc.perform(get("/api/projects/" + projectId + "/columns").header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long columnId = ((Number) JsonPath.read(columnJson, "$[0].id")).longValue();
        mvc.perform(post("/api/projects/" + projectId + "/cards").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + columnId + ",\"cardType\":\"ISSUE\",\"issueId\":" + issueId + "}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/projects/" + projectId + "/cards").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + columnId + ",\"cardType\":\"PULL_REQUEST\",\"pullRequestId\":" + pullRequestId + "}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/projects/" + projectId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(projectRows.existsById(projectId)).isFalse();
        assertThat(projectCardRows.countByProject_Id(projectId)).isZero();
        assertThat(projectColumnRows.countByProject_Id(projectId)).isZero();
        assertThat(issueRows.existsById(issueId)).isTrue();
        assertThat(pullRequestRows.existsById(pullRequestId)).isTrue();
        mvc.perform(delete("/api/projects/" + projectId).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingRepositoryProjectAllowsRepositoryDeletionAfterProjectCleanup() throws Exception {
        Account owner = register("projectrepocleanup");
        long repositoryId = createRepository(owner, "PUBLIC");
        long projectId = createProject(owner, "REPOSITORY", repositoryId, "Cleanup board");
        mvc.perform(delete("/api/projects/" + projectId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/repositories/" + repositoryId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(projectRows.existsById(projectId)).isFalse();
        assertThat(repositoryRows.existsById(repositoryId)).isFalse();
    }

    @Test
    void projectDeletionRollsBackBoardRowsWhenTransactionFails() throws Exception {
        Account owner = register("projectdeleterollback");
        long projectId = createProject(owner, "PERSONAL", null, "Rollback deletion");
        String columns = mvc.perform(get("/api/projects/" + projectId + "/columns").header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long columnId = ((Number) JsonPath.read(columns, "$[0].id")).longValue();
        createNote(owner, projectId, columnId, "Must survive rollback");
        long cardCount = projectCardRows.countByProject_Id(projectId);
        long columnCount = projectColumnRows.countByProject_Id(projectId);
        var user = userRows.findById(owner.id()).orElseThrow();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            projectService.delete(projectId, user.getId());
            throw new IllegalStateException("simulate a failure before transaction commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(projectRows.existsById(projectId)).isTrue();
        assertThat(projectCardRows.countByProject_Id(projectId)).isEqualTo(cardCount);
        assertThat(projectColumnRows.countByProject_Id(projectId)).isEqualTo(columnCount);
    }

    @Test
    void concurrentCardCreationAndMovesKeepUniqueContiguousPositions() throws Exception {
        Account owner = register("projectconcurrent"); long project = createProject(owner, "PERSONAL", null, "Concurrent board");
        String cols = mvc.perform(get("/api/projects/" + project + "/columns").header("Authorization", bearer(owner))).andReturn().getResponse().getContentAsString();
        long todo = ((Number) JsonPath.read(cols, "$[0].id")).longValue(); long doing = ((Number) JsonPath.read(cols, "$[1].id")).longValue();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var calls = java.util.stream.IntStream.range(0, 8).mapToObj(i -> (Callable<Long>) () -> createNote(owner, project, todo, "Concurrent " + i)).toList();
            var results = pool.invokeAll(calls); java.util.List<Long> ids = new java.util.ArrayList<>();
            for (var result : results) ids.add(result.get());
            var moves = ids.stream().limit(4).map(id -> (Callable<Integer>) () -> mvc.perform(post("/api/project-cards/" + id + "/move")
                    .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"columnId\":" + doing + "}")).andReturn().getResponse().getStatus()).toList();
            for (var result : pool.invokeAll(moves)) assertThat(result.get()).isEqualTo(200);
        } finally { pool.shutdownNow(); }
        mvc.perform(get("/api/projects/" + project + "/cards").header("Authorization", bearer(owner)).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(8));
        mvc.perform(get("/api/projects/" + project + "/board").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.columns[0].cards.length()").value(4))
                .andExpect(jsonPath("$.columns[1].cards.length()").value(4));
    }

    @Test
    void concurrentColumnDeletionAndCardCreationPreserveOneValidOutcome() throws Exception {
        Account owner = register("projectcolrace");
        long project = createProject(owner, "PERSONAL", null, "Column race");
        String cols = mvc.perform(get("/api/projects/" + project + "/columns").header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long column = ((Number) JsonPath.read(cols, "$[0].id")).longValue();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> delete = () -> {
                ready.countDown(); start.await();
                return mvc.perform(delete("/api/project-columns/" + column).header("Authorization", bearer(owner)))
                        .andReturn().getResponse().getStatus();
            };
            Callable<Integer> create = () -> {
                ready.countDown(); start.await();
                return mvc.perform(post("/api/projects/" + project + "/cards").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columnId\":" + column + ",\"cardType\":\"NOTE\",\"title\":\"Racing card\"}"))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> deleted = pool.submit(delete);
            Future<Integer> created = pool.submit(create);
            ready.await(); start.countDown();
            int deleteStatus = deleted.get();
            int createStatus = created.get();
            if (deleteStatus == 204) {
                assertThat(createStatus).isEqualTo(404);
                mvc.perform(get("/api/projects/" + project + "/board").header("Authorization", bearer(owner)))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.columns.length()", org.hamcrest.Matchers.is(3)));
            } else {
                assertThat(deleteStatus).isEqualTo(409);
                assertThat(createStatus).isEqualTo(201);
                mvc.perform(get("/api/projects/" + project + "/board").header("Authorization", bearer(owner)))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.columns[0].cards.length()", org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pullRequestCardsLinkOnlyReadableSameRepositoryResources() throws Exception {
        Account owner = register("projectprowner"); long repository = createRepository(owner, "PUBLIC");
        var repositoryEntity = repositoryRows.findById(repository).orElseThrow();
        var author = userRows.findById(owner.id()).orElseThrow();
        Tree tree = treeRows.saveAndFlush(new Tree(hash()));
        Commit base = commitRows.saveAndFlush(new Commit(repositoryEntity, hash(), "Base", author, tree, null));
        Commit head = commitRows.saveAndFlush(new Commit(repositoryEntity, hash(), "Feature", author, tree, base));
        Branch main = branchRows.saveAndFlush(new Branch(repositoryEntity, "main", base));
        Branch feature = branchRows.saveAndFlush(new Branch(repositoryEntity, "feature", head));
        PullRequest pr = pullRequestRows.saveAndFlush(new PullRequest(repositoryEntity, 1, "Feature PR", null, author,
                feature, main, head, base));
        long pullRequestId = pr.getId();
        long project = createProject(owner, "REPOSITORY", repository, "PR board");
        String columnJson = mvc.perform(get("/api/projects/" + project + "/columns").header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long column = ((Number) JsonPath.read(columnJson, "$[0].id")).longValue();
        mvc.perform(post("/api/projects/" + project + "/cards").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + column + ",\"cardType\":\"PULL_REQUEST\",\"pullRequestId\":" + pullRequestId + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pullRequestId").value(pullRequestId)).andExpect(jsonPath("$.title").value("Feature PR"));
    }

    @Test
    void projectCreationActivityRollsBackWithProjectTransaction() throws Exception {
        Account owner = register("projectrollback"); long repositoryId = createRepository(owner, "PUBLIC");
        long before = projectRows.count(); long activityBefore = activities.countByRepository_Id(repositoryId);
        var repository = repositoryRows.findById(repositoryId).orElseThrow();
        var user = userRows.findByUsernameIgnoreCase(owner.username()).orElseThrow();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            projectService.create(new CreateProjectRequest("Rollback board", null, ProjectScope.REPOSITORY, repositoryId), user.getId());
            throw new IllegalStateException("rollback test");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(projectRows.count()).isEqualTo(before);
        assertThat(activities.countByRepository_Id(repository.getId())).isEqualTo(activityBefore);
    }

    private long createProject(Account a, String scope, Long repo, String name) throws Exception {
        String body = "{\"scope\":\"" + scope + "\",\"name\":\"" + name + "\"" + (repo == null ? "" : ",\"repositoryId\":" + repo) + "}";
        String response = mvc.perform(post("/api/projects").header("Authorization", bearer(a)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
    private long createNote(Account a, long project, long column, String title) throws Exception {
        String response = mvc.perform(post("/api/projects/" + project + "/cards").header("Authorization", bearer(a)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"columnId\":" + column + ",\"cardType\":\"NOTE\",\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
    private long createRepository(Account a, String visibility) throws Exception {
        String name = "phase10-" + IDS.incrementAndGet() + "-" + System.nanoTime();
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(a)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
    private void invite(Account owner, Account invitee, long repo, String role) throws Exception {
        String response = mvc.perform(post("/api/repositories/" + repo + "/collaborators/invitations").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + invitee.username() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long invitation = ((Number) JsonPath.read(response, "$.invitationId")).longValue();
        mvc.perform(post("/api/collaboration-invitations/" + invitation + "/accept").header("Authorization", bearer(invitee))).andExpect(status().isOk());
    }
    private Account register(String prefix) throws Exception {
        String username = prefix + IDS.incrementAndGet() + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(username, JsonPath.read(response, "$.accessToken"), ((Number) JsonPath.read(response, "$.user.id")).longValue());
    }
    private String bearer(Account account) { return "Bearer " + account.token(); }
    private String hash() { return String.format("%064x", IDS.incrementAndGet()); }
    private record Account(String username, String token, Long id) { }
}
