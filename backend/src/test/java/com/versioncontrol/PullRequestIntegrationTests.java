package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.PullRequestState;

@SpringBootTest
@AutoConfigureMockMvc
class PullRequestIntegrationTests {
    private static final AtomicLong NAMES = new AtomicLong();
    @Autowired MockMvc mvc;
    @Autowired PullRequestRepository pullRequests;

    @Test
    void createsListsUpdatesClosesAndReopensPullRequests() throws Exception {
        Account owner = register("prlifecycle");
        Account other = register("prviewer");
        long repo = createRepository(owner, "PUBLIC");
        String main = initialize(owner, repo);
        String feature = createBranch(owner, repo, "feature/pr", main);
        String featureCommit = createCommit(owner, repo, "feature/pr", feature, "feature change");
        String createUrl = "/api/repositories/" + repo + "/pull-requests";

        mvc.perform(post(createUrl).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Add feature\",\"description\":\"Details\",\"sourceBranch\":\"feature/pr\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(createUrl).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Add feature\",\"sourceBranch\":\"feature/pr\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isForbidden());

        String created = mvc.perform(post(createUrl).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" Add feature \",\"description\":\"Details\",\"sourceBranch\":\"feature/pr\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.title").value("Add feature"))
                .andExpect(jsonPath("$.sourceHeadHashAtCreation").value(featureCommit))
                .andExpect(jsonPath("$.targetHeadHashAtCreation").value(main))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");
        String url = "/api/pull-requests/" + id;

        mvc.perform(get(createUrl)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.author.username").isNotEmpty());
        mvc.perform(put(url).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"forbidden\"}")).andExpect(status().isForbidden());
        mvc.perform(put(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Updated title\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Updated title"));
        mvc.perform(put(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"MERGED\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/pull-requests/9223372036854770000").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(get(createUrl + "?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get(createUrl + "?size=101")).andExpect(status().isBadRequest());

        mvc.perform(post(url + "/close").header("Authorization", bearer(other))).andExpect(status().isForbidden());
        CountDownLatch closeStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> closeAtBarrier(closeStart, url, owner));
            var b = executor.submit(() -> closeAtBarrier(closeStart, url, owner));
            closeStart.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactly(200, 200);
        }
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CLOSED")).andExpect(jsonPath("$.closedAt").isNotEmpty());
        mvc.perform(post(url + "/close").header("Authorization", bearer(owner))).andExpect(status().isOk());
        mvc.perform(post(url + "/reopen").header("Authorization", bearer(other))).andExpect(status().isForbidden());
        String second = mvc.perform(post(createUrl).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Follow-up\",\"sourceBranch\":\"feature/pr\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(2))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post(url + "/reopen").header("Authorization", bearer(owner))).andExpect(status().isConflict());
        Number secondId = JsonPath.read(second, "$.id");
        mvc.perform(post("/api/pull-requests/" + secondId + "/close").header("Authorization", bearer(owner))).andExpect(status().isOk());
        mvc.perform(post("/api/pull-requests/" + secondId + "/reopen").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN")).andExpect(jsonPath("$.closedAt").doesNotExist());
        PullRequest merged = pullRequests.findDetailedById(secondId.longValue()).orElseThrow();
        merged.setState(PullRequestState.MERGED);
        pullRequests.saveAndFlush(merged);
        mvc.perform(post("/api/pull-requests/" + secondId + "/reopen").header("Authorization", bearer(owner))).andExpect(status().isConflict());
        mvc.perform(post("/api/pull-requests/" + secondId + "/close").header("Authorization", bearer(owner))).andExpect(status().isConflict());
        assertThat(pullRequests.countByRepository_Id(repo)).isEqualTo(2);
        assertThat(featureCommit).isNotEqualTo(main);
    }

    @Test
    void rejectsInvalidBranchPairsAndKeepsRepositoryScopedNumbers() throws Exception {
        Account owner = register("prpairs");
        long firstRepo = createRepository(owner, "PUBLIC");
        String main1 = initialize(owner, firstRepo);
        String endpoint1 = "/api/repositories/" + firstRepo + "/pull-requests";
        mvc.perform(post(endpoint1).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"same\",\"sourceBranch\":\"main\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isBadRequest());
        createBranch(owner, firstRepo, "feature/equal", main1);
        mvc.perform(post(endpoint1).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"equal heads\",\"sourceBranch\":\"feature/equal\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(endpoint1).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"equal\",\"sourceBranch\":\"main\",\"targetBranch\":\"missing\"}"))
                .andExpect(status().isNotFound());

        String firstFeature = createBranch(owner, firstRepo, "feature/one", main1);
        String firstHead = createCommit(owner, firstRepo, "feature/one", firstFeature, "one");
        String bodyOne = "{\"title\":\"one\",\"sourceBranch\":\"feature/one\",\"targetBranch\":\"main\"}";
        mvc.perform(post(endpoint1).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(bodyOne))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(1));
        mvc.perform(post(endpoint1).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(bodyOne))
                .andExpect(status().isConflict());
        assertThat(firstHead).isNotEqualTo(main1);
        String ahead = createBranch(owner, firstRepo, "feature/ahead", firstHead);
        createCommit(owner, firstRepo, "feature/ahead", ahead, "ahead commit");
        mvc.perform(post(endpoint1).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"already contained\",\"sourceBranch\":\"feature/one\",\"targetBranch\":\"feature/ahead\"}"))
                .andExpect(status().isConflict());

        long secondRepo = createRepository(owner, "PUBLIC");
        String main2 = initialize(owner, secondRepo);
        String feature2 = createBranch(owner, secondRepo, "feature/one", main2);
        createCommit(owner, secondRepo, "feature/one", feature2, "another repo commit");
        mvc.perform(post("/api/repositories/" + secondRepo + "/pull-requests").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(bodyOne))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(1));
        createBranch(owner, secondRepo, "feature/foreign", main2);
        mvc.perform(post(endpoint1).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"foreign branch\",\"sourceBranch\":\"feature/foreign\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentCreationSerializesRepositoryNumberAndDuplicateOpenPair() throws Exception {
        Account owner = register("prrace");
        long repo = createRepository(owner, "PUBLIC");
        String main = initialize(owner, repo);
        String feature = createBranch(owner, repo, "feature/race", main);
        createCommit(owner, repo, "feature/race", feature, "race commit");
        String url = "/api/repositories/" + repo + "/pull-requests";
        String body = "{\"title\":\"race\",\"sourceBranch\":\"feature/race\",\"targetBranch\":\"main\"}";
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> createAtBarrier(start, url, owner, body));
            var b = executor.submit(() -> createAtBarrier(start, url, owner, body));
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(pullRequests.countByRepository_Id(repo)).isEqualTo(1);
    }

    @Test
    void publicAndPrivateVisibilityAndMissingResourcesFollowRepositoryConventions() throws Exception {
        Account owner = register("prprivate");
        Account other = register("prprivateviewer");
        long repo = createRepository(owner, "PRIVATE");
        initialize(owner, repo);
        String list = "/api/repositories/" + repo + "/pull-requests";
        mvc.perform(get(list)).andExpect(status().isNotFound());
        mvc.perform(get(list).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get(list).header("Authorization", bearer(owner))).andExpect(status().isOk());
        mvc.perform(get("/api/pull-requests/9223372036854770000")).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/9223372036854770000/pull-requests")).andExpect(status().isNotFound());
        long uninitialized = createRepository(owner, "PUBLIC");
        mvc.perform(post("/api/repositories/" + uninitialized + "/pull-requests").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"no init\",\"sourceBranch\":\"main\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/repositories/9223372036854770000/pull-requests").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/repositories/9223372036854770000/pull-requests").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"valid\",\"sourceBranch\":\"main\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isNotFound());
    }

    private int createAtBarrier(CountDownLatch latch, String url, Account account, String body) throws Exception {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("requests did not reach barrier");
        return mvc.perform(post(url).header("Authorization", bearer(account)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }
    private int closeAtBarrier(CountDownLatch latch, String url, Account account) throws Exception {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("close requests did not reach barrier");
        return mvc.perform(post(url + "/close").header("Authorization", bearer(account))).andReturn().getResponse().getStatus();
    }
    private long createRepository(Account account, String visibility) throws Exception {
        String name = "prrepo-" + NAMES.incrementAndGet() + "-" + System.nanoTime();
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
    private String initialize(Account account, long repo) throws Exception {
        String result = mvc.perform(post("/api/repositories/" + repo + "/git/init").header("Authorization", bearer(account)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(result, "$.commitHash");
    }
    private String createBranch(Account account, long repo, String name, String from) throws Exception {
        mvc.perform(post("/api/repositories/" + repo + "/branches").header("Authorization", bearer(account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"fromCommitHash\":\"" + from + "\"}"))
                .andExpect(status().isCreated());
        return from;
    }
    private String createCommit(Account account, long repo, String branch, String expected, String message) throws Exception {
        String result = mvc.perform(post("/api/repositories/" + repo + "/commits").header("Authorization", bearer(account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"" + branch + "\",\"expectedHeadHash\":\"" + expected + "\",\"message\":\"" + message
                        + "\",\"changes\":[{\"path\":\"" + message.replace(' ', '-') + ".txt\",\"content\":\"content-" + message + "\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(result, "$.commitHash");
    }
    private Account register(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(response, "$.user.id")).longValue(), JsonPath.read(response, "$.accessToken"));
    }
    private String bearer(Account a) { return "Bearer " + a.token(); }
    private record Account(Long id, String token) { }
}
