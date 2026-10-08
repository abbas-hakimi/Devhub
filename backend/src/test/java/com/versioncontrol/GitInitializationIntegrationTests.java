package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.TreeRepository;
import com.versioncontrol.versioncontrol.hash.CommitCanonicalizer;
import com.versioncontrol.versioncontrol.hash.TreeCanonicalizer;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;

@SpringBootTest
@AutoConfigureMockMvc
class GitInitializationIntegrationTests {
    private static final String INITIAL_MESSAGE = "Initial commit";

    @Autowired MockMvc mvc;
    @Autowired BranchRepository branches;
    @Autowired CommitRepository commits;
    @Autowired TreeRepository trees;
    @Autowired ObjectStorage objectStorage;

    @Test
    void ownerCanInitializeOnceAndRepeatWithoutDuplicateObjects() throws Exception {
        TestAccount owner = register("gitinit");
        long repositoryId = createRepository(owner, "initializable");
        String url = "/api/repositories/" + repositoryId + "/git/init";
        String emptyTreeHash = TreeCanonicalizer.hash(List.of());

        String first = mvc.perform(post(url).header("Authorization", bearer(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryId").value(repositoryId))
                .andExpect(jsonPath("$.branchName").value("main"))
                .andExpect(jsonPath("$.rootTreeHash").value(emptyTreeHash))
                .andExpect(jsonPath("$.status").value("INITIALIZED"))
                .andReturn().getResponse().getContentAsString();
        String firstCommitHash = JsonPath.read(first, "$.commitHash");

        assertThat(branches.countByRepository_IdAndName(repositoryId, "main")).isEqualTo(1);
        assertThat(commits.countByRepository_Id(repositoryId)).isEqualTo(1);
        assertThat(trees.countByTreeHash(emptyTreeHash)).isEqualTo(1);
        Branch main = branches.findByRepository_IdAndName(repositoryId, "main").orElseThrow();
        Commit initial = main.getHeadCommit();
        assertThat(initial.getParentCommit()).isNull();
        assertThat(initial.getAuthor().getId()).isEqualTo(owner.id);
        assertThat(initial.getMessage()).isEqualTo(INITIAL_MESSAGE);
        assertThat(initial.getRootTree().getTreeHash()).isEqualTo(emptyTreeHash);
        assertThat(initial.getCreatedAt().getNano() % 1_000).isZero();
        assertThat(CommitCanonicalizer.hash(repositoryId, null, emptyTreeHash, owner.id,
                initial.getCreatedAt().toInstant(ZoneOffset.UTC), INITIAL_MESSAGE)).isEqualTo(initial.getCommitHash());
        assertThat(objectStorage.exists(emptyTreeHash)).isTrue();
        assertThat(objectStorage.exists(firstCommitHash)).isTrue();
        try (var storedTree = objectStorage.read(emptyTreeHash)) {
            assertThat(storedTree.readAllBytes()).isEqualTo(TreeCanonicalizer.canonicalize(List.of()));
        }

        mvc.perform(post(url).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitHash").value(firstCommitHash))
                .andExpect(jsonPath("$.status").value("ALREADY_INITIALIZED"));
        assertThat(branches.countByRepository_IdAndName(repositoryId, "main")).isEqualTo(1);
        assertThat(commits.countByRepository_Id(repositoryId)).isEqualTo(1);
        assertThat(trees.countByTreeHash(emptyTreeHash)).isEqualTo(1);
    }

    @Test
    void requiresAuthenticationOwnerAndExistingRepository() throws Exception {
        TestAccount owner = register("gitowner");
        TestAccount other = register("gitother");
        long repositoryId = createRepository(owner, "protected-init");
        String url = "/api/repositories/" + repositoryId + "/git/init";

        mvc.perform(post(url)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).header("Authorization", bearer(other))).andExpect(status().isForbidden());
        mvc.perform(post("/api/repositories/9223372036854770000/git/init")
                .header("Authorization", bearer(owner))).andExpect(status().isNotFound());
        assertThat(branches.countByRepository_IdAndName(repositoryId, "main")).isZero();
        assertThat(commits.countByRepository_Id(repositoryId)).isZero();
    }

    @Test
    void concurrentInitializationCreatesOneMainBranchAndOneInitialCommit() throws Exception {
        TestAccount owner = register("gitconcurrent");
        long repositoryId = createRepository(owner, "concurrent-init");
        String url = "/api/repositories/" + repositoryId + "/git/init";
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> performAfter(start, url, owner));
            var second = executor.submit(() -> performAfter(start, url, owner));
            start.countDown();
            int firstStatus = first.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            int secondStatus = second.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(201, 200);
        }

        assertThat(branches.countByRepository_IdAndName(repositoryId, "main")).isEqualTo(1);
        assertThat(commits.countByRepository_Id(repositoryId)).isEqualTo(1);
        assertThat(trees.countByTreeHash(TreeCanonicalizer.hash(List.of()))).isEqualTo(1);
    }

    private MvcResult performAfter(CountDownLatch start, String url, TestAccount account) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent request did not start");
        return mvc.perform(post(url).header("Authorization", bearer(account)).contentType(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private long createRepository(TestAccount account, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"visibility\":\"PUBLIC\"}";
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(account))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number repositoryId = JsonPath.read(response, "$.id");
        return repositoryId.longValue();
    }

    private TestAccount register(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.test\",\"password\":\"StrongPass123\"}";
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number userId = JsonPath.read(response, "$.user.id");
        return new TestAccount(userId.longValue(), JsonPath.read(response, "$.accessToken"));
    }

    private String bearer(TestAccount account) { return "Bearer " + account.token; }

    private record TestAccount(Long id, String token) { }
}
