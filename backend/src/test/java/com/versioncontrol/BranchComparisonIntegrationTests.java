package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.TreeRepository;

@SpringBootTest
@AutoConfigureMockMvc
class BranchComparisonIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired BranchRepository branches;
    @Autowired CommitRepository commits;
    @Autowired TreeRepository trees;
    @Value("${version-control.storage.root}") String storageRoot;

    @Test
    void comparesDivergedSnapshotsAndReturnsDeterministicTextAndBinaryChanges() throws Exception {
        Account owner = register("comparechanges");
        long repositoryId = createRepository(owner, "PUBLIC");
        initialize(owner, repositoryId);
        String initial = head(repositoryId, "main");
        createBranch(owner, repositoryId, "feature/auth", initial);
        commit(owner, repositoryId, "feature/auth", initial,
                "[{\"path\":\"feature.txt\",\"content\":\"without final newline\"},"
                + "{\"path\":\"mod.txt\",\"content\":\"same\\r\\nnew\\r\\n\"},"
                + "{\"path\":\"nested/added.txt\",\"content\":\"nested\"},"
                + "{\"path\":\"binary.dat\",\"contentBase64\":\"AAEC/w==\"},"
                + "{\"path\":\"\uE000.txt\",\"content\":\"bmp\"},"
                + "{\"path\":\"😀.txt\",\"content\":\"astral\"}]");
        commit(owner, repositoryId, "main", initial,
                "[{\"path\":\"deleted.txt\",\"content\":\"gone\"},"
                + "{\"path\":\"mod.txt\",\"content\":\"same\\nold\\n\"}]");
        String mainHead = head(repositoryId, "main");
        String featureHead = head(repositoryId, "feature/auth");

        var response = mvc.perform(get("/api/repositories/{id}/compare", repositoryId)
                        .param("base", "main").param("head", "feature/auth").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCommitHash").value(mainHead))
                .andExpect(jsonPath("$.headCommitHash").value(featureHead))
                .andExpect(jsonPath("$.mergeBaseHash").value(initial))
                .andExpect(jsonPath("$.aheadBy").value(1))
                .andExpect(jsonPath("$.behindBy").value(1))
                .andExpect(jsonPath("$.filesChanged").value(7))
                .andExpect(jsonPath("$.filesAdded").value(5))
                .andExpect(jsonPath("$.filesModified").value(1))
                .andExpect(jsonPath("$.filesDeleted").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(response, "$.changes[0].path")).isEqualTo("binary.dat");
        assertThat((Boolean) JsonPath.read(response, "$.changes[0].binary")).isTrue();
        assertThat((Object) JsonPath.read(response, "$.changes[0].patch")).isNull();
        assertThat((String) JsonPath.read(response, "$.changes[1].path")).isEqualTo("deleted.txt");
        assertThat((String) JsonPath.read(response, "$.changes[1].status")).isEqualTo("DELETED");
        assertThat((String) JsonPath.read(response, "$.changes[2].path")).isEqualTo("feature.txt");
        assertThat((String) JsonPath.read(response, "$.changes[2].patch")).contains("\\ No newline at end of file");
        assertThat((String) JsonPath.read(response, "$.changes[3].path")).isEqualTo("mod.txt");
        assertThat((String) JsonPath.read(response, "$.changes[3].patch")).contains("+new", "-old").doesNotContain("\r");
        assertThat((Number) JsonPath.read(response, "$.changes[3].additions")).isEqualTo(1);
        assertThat((Number) JsonPath.read(response, "$.changes[3].deletions")).isEqualTo(1);
        assertThat((String) JsonPath.read(response, "$.changes[4].path")).isEqualTo("nested/added.txt");
        assertThat((String) JsonPath.read(response, "$.changes[5].path")).isEqualTo("\uE000.txt");
        assertThat((String) JsonPath.read(response, "$.changes[6].path")).isEqualTo("😀.txt");

        long commitCount = commits.countByRepository_Id(repositoryId);
        long treeCount = countTreesForTest();
        mvc.perform(get("/api/repositories/{id}/compare", repositoryId).param("base", "main").param("head", "feature/auth"))
                .andExpect(status().isOk());
        assertThat(commits.countByRepository_Id(repositoryId)).isEqualTo(commitCount);
        assertThat(countTreesForTest()).isEqualTo(treeCount);
    }

    @Test
    void identicalSnapshotsSkipSubtreesAndPrDiffUsesCurrentBranchHeads() throws Exception {
        Account owner = register("comparepr");
        long repositoryId = createRepository(owner, "PUBLIC");
        initialize(owner, repositoryId);
        String initial = head(repositoryId, "main");
        createBranch(owner, repositoryId, "snapshot", initial);
        mvc.perform(get("/api/repositories/{id}/compare", repositoryId).param("base", "main").param("head", "snapshot"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mergeBaseHash").value(initial))
                .andExpect(jsonPath("$.aheadBy").value(0)).andExpect(jsonPath("$.behindBy").value(0))
                .andExpect(jsonPath("$.filesChanged").value(0)).andExpect(jsonPath("$.changes.length()").value(0));

        createBranch(owner, repositoryId, "feature/live", initial);
        String feature1 = commit(owner, repositoryId, "feature/live", initial,
                "[{\"path\":\"first.txt\",\"content\":\"one\"}]");
        String prJson = mvc.perform(post("/api/repositories/{id}/pull-requests", repositoryId)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"live diff\",\"sourceBranch\":\"feature/live\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number prId = JsonPath.read(prJson, "$.id");
        String feature2 = commit(owner, repositoryId, "feature/live", feature1,
                "[{\"path\":\"later.txt\",\"content\":\"two\"}]");
        mvc.perform(get("/api/pull-requests/{id}/diff", prId.longValue())).andExpect(status().isOk())
                .andExpect(jsonPath("$.baseBranch").value("main"))
                .andExpect(jsonPath("$.headBranch").value("feature/live"))
                .andExpect(jsonPath("$.headCommitHash").value(feature2))
                .andExpect(jsonPath("$.filesAdded").value(2));
    }

    @Test
    void enforcesVisibilityAndValidatesBranchesAndPaging() throws Exception {
        Account owner = register("compareprivate");
        Account other = register("compareother");
        long privateId = createRepository(owner, "PRIVATE");
        initialize(owner, privateId);
        mvc.perform(get("/api/repositories/{id}/compare", privateId).param("base", "main").param("head", "main"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/{id}/compare", privateId).header("Authorization", bearer(other))
                        .param("base", "main").param("head", "main"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/{id}/compare", privateId).header("Authorization", bearer(owner))
                        .param("base", "main").param("head", "main"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/repositories/{id}/compare", privateId).param("base", "../bad").param("head", "main"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/{id}/compare", privateId).param("base", "missing").param("head", "main"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/{id}/compare", privateId).param("base", "main").param("head", "main").param("size", "11"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/{id}/compare", 99999999L).param("base", "main").param("head", "main"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/pull-requests/{id}/diff", 99999999L)).andExpect(status().isNotFound());
    }

    @Test
    void boundsLargePatchesAndReportsMissingOrCorruptedStoredBlobs() throws Exception {
        Account owner = register("comparelarge");
        long repositoryId = createRepository(owner, "PUBLIC");
        initialize(owner, repositoryId);
        String initial = head(repositoryId, "main");
        createBranch(owner, repositoryId, "feature/large", initial);
        byte[] large = "line\n".repeat(30_000).getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(large);
        String largeHead = commit(owner, repositoryId, "feature/large", initial,
                "[{\"path\":\"large.txt\",\"contentBase64\":\"" + encoded + "\"}]");
        mvc.perform(get("/api/repositories/{id}/compare", repositoryId).param("base", "main").param("head", "feature/large"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.headCommitHash").value(largeHead))
                .andExpect(jsonPath("$.changes[0].patch").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].patchTruncated").value(true))
                .andExpect(jsonPath("$.changes[0].binary").value(false));

        String smallHead = commit(owner, repositoryId, "feature/large", largeHead,
                "[{\"path\":\"small.txt\",\"content\":\"original bytes\"}]");
        String compareJson = mvc.perform(get("/api/repositories/{id}/compare", repositoryId)
                        .param("base", "main").param("head", "feature/large").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.headCommitHash").value(smallHead))
                .andReturn().getResponse().getContentAsString();
        String hash = JsonPath.read(compareJson, "$.changes[0].newHash");
        Path object = Path.of(storageRoot, "objects", hash.substring(0, 2), hash.substring(2));
        byte[] original = Files.readAllBytes(object);
        try {
            Files.write(object, "corrupted data".getBytes(StandardCharsets.UTF_8));
            mvc.perform(get("/api/repositories/{id}/compare", repositoryId).param("base", "main").param("head", "feature/large")
                    .param("page", "1").param("size", "1")).andExpect(status().isInternalServerError());
            Files.delete(object);
            mvc.perform(get("/api/repositories/{id}/compare", repositoryId).param("base", "main").param("head", "feature/large")
                    .param("page", "1").param("size", "1")).andExpect(status().isInternalServerError());
        } finally {
            Files.write(object, original);
        }
    }

    private long countTreesForTest() { return trees.count(); }
    private long createRepository(Account owner, String visibility) throws Exception {
        String json = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"compare-" + System.nanoTime() + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
    private void initialize(Account owner, long repositoryId) throws Exception {
        mvc.perform(post("/api/repositories/{id}/git/init", repositoryId).header("Authorization", bearer(owner)))
                .andExpect(status().isCreated());
    }
    private String head(long repositoryId, String name) {
        Branch branch = branches.findByRepository_IdAndName(repositoryId, name).orElseThrow();
        return branch.getHeadCommit().getCommitHash();
    }
    private void createBranch(Account owner, long repositoryId, String name, String fromHash) throws Exception {
        mvc.perform(post("/api/repositories/{id}/branches", repositoryId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"fromCommitHash\":\"" + fromHash + "\"}"))
                .andExpect(status().isCreated());
    }
    private String commit(Account owner, long repositoryId, String branch, String expectedHead, String changes) throws Exception {
        String result = mvc.perform(post("/api/repositories/{id}/commits", repositoryId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"" + branch + "\",\"expectedHeadHash\":\""
                                + expectedHead + "\",\"message\":\"diff test\",\"changes\":" + changes + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(result, "$.commitHash");
    }
    private Account register(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(result, "$.user.id")).longValue(), JsonPath.read(result, "$.accessToken"));
    }
    private String bearer(Account account) { return "Bearer " + account.token(); }
    private record Account(long id, String token) { }
}
