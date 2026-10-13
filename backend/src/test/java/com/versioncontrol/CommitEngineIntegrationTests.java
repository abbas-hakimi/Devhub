package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import com.versioncontrol.entity.TreeEntry;
import com.versioncontrol.repository.BlobRepository;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.TreeEntryRepository;
import com.versioncontrol.repository.TreeRepository;
import com.versioncontrol.versioncontrol.hash.CommitCanonicalizer;
import com.versioncontrol.versioncontrol.hash.Sha256Hasher;
import com.versioncontrol.versioncontrol.hash.TreeCanonicalizer;
import com.versioncontrol.versioncontrol.hash.TreeHashInput;
import com.versioncontrol.entity.TreeEntryType;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;

@SpringBootTest
@AutoConfigureMockMvc
class CommitEngineIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired BranchRepository branches;
    @Autowired CommitRepository commits;
    @Autowired TreeRepository trees;
    @Autowired TreeEntryRepository entries;
    @Autowired BlobRepository blobs;
    @Autowired ObjectStorage storage;

    @Test
    void createsCommitAndPersistsCanonicalCommitAndBlobObjects() throws Exception {
        Account owner = register("commitowner");
        long repositoryId = repository(owner, "first-commit", "PUBLIC");
        init(owner, repositoryId);
        String oldHead = main(repositoryId).getHeadCommit().getCommitHash();

        String body = "{\"branch\":\"main\",\"expectedHeadHash\":\"" + oldHead
                + "\",\"message\":\"Add README\",\"changes\":[{\"path\":\"README.md\",\"content\":\"# Version Control\"}]}";
        String response = mvc.perform(post(commitUrl(repositoryId)).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.parentCommitHash").value(oldHead)).andReturn().getResponse().getContentAsString();
        String hash = JsonPath.read(response, "$.commitHash");
        Branch main = main(repositoryId);
        Commit commit = main.getHeadCommit();
        assertThat(commit.getCommitHash()).isEqualTo(hash);
        assertThat(commit.getParentCommit().getCommitHash()).isEqualTo(oldHead);
        assertThat(commit.getCreatedAt().getNano() % 1_000).isZero();
        assertThat(CommitCanonicalizer.hash(repositoryId, oldHead, commit.getRootTree().getTreeHash(), owner.id,
                commit.getCreatedAt().toInstant(ZoneOffset.UTC), "Add README")).isEqualTo(hash);
        List<TreeEntry> rootEntries = entries.findByTree_IdOrderByNameAsc(commit.getRootTree().getId());
        assertThat(rootEntries).extracting(TreeEntry::getName).containsExactly("README.md");
        String expectedBlobHash = Sha256Hasher.hashBytes("# Version Control".getBytes(StandardCharsets.UTF_8));
        assertThat(commit.getRootTree().getTreeHash()).isEqualTo(TreeCanonicalizer.hash(
                List.of(new TreeHashInput("README.md", TreeEntryType.FILE, expectedBlobHash))));
        assertThat(rootEntries.getFirst().getBlob().getObjectHash()).isEqualTo(expectedBlobHash);
        assertThat(blobs.findByObjectHash(expectedBlobHash)).isPresent();
        assertThat(storage.read(expectedBlobHash).readAllBytes()).isEqualTo("# Version Control".getBytes(StandardCharsets.UTF_8));
        assertThat(trees.findByTreeHash(commit.getRootTree().getTreeHash())).isPresent();
    }

    @Test
    void reusesBlobsAndBuildsNestedImmutableSnapshotsAcrossReplaceAndDelete() throws Exception {
        Account owner = register("snapshotowner");
        long repositoryId = repository(owner, "snapshot", "PUBLIC");
        init(owner, repositoryId);
        String head = main(repositoryId).getHeadCommit().getCommitHash();
        commit(owner, repositoryId, "{\"branch\":\"main\",\"message\":\"Add files\",\"changes\":["
                + "{\"path\":\"README.md\",\"content\":\"same\"},"
                + "{\"path\":\"keep.txt\",\"content\":\"keep\"}]}" );
        Commit first = main(repositoryId).getHeadCommit();
        var sameBlob = blobs.findByObjectHash(Sha256Hasher.hashBytes("same".getBytes(StandardCharsets.UTF_8))).orElseThrow();

        commit(owner, repositoryId, "{\"branch\":\"main\",\"expectedHeadHash\":\"" + first.getCommitHash()
                + "\",\"message\":\"Update files\",\"changes\":["
                + "{\"path\":\"README.md\",\"content\":\"changed\"},"
                + "{\"path\":\"src/utils/helper.java\",\"contentBase64\":\"AAECAwQ=\"},"
                + "{\"path\":\"copy.txt\",\"content\":\"keep\"}]}" );
        Commit second = main(repositoryId).getHeadCommit();
        assertThat(second.getParentCommit().getCommitHash()).isEqualTo(first.getCommitHash());
        assertThat(first.getRootTree().getTreeHash()).isNotEqualTo(second.getRootTree().getTreeHash());
        var duplicateBlob = blobs.findByObjectHash(Sha256Hasher.hashBytes("keep".getBytes(StandardCharsets.UTF_8))).orElseThrow();
        assertThat(duplicateBlob.getId()).isEqualTo(entries.findByTree_IdOrderByNameAsc(second.getRootTree().getId())
                .stream().filter(e -> e.getName().equals("copy.txt")).findFirst().orElseThrow().getBlob().getId());
        assertThat(blobs.findByObjectHash(sameBlob.getObjectHash())).isPresent();
        assertThat(storage.read(Sha256Hasher.hashBytes(new byte[] {0, 1, 2, 3, 4})).readAllBytes())
                .containsExactly(0, 1, 2, 3, 4);
        TreeEntry src = entries.findByTree_IdOrderByNameAsc(second.getRootTree().getId()).stream()
                .filter(e -> e.getName().equals("src")).findFirst().orElseThrow();
        assertThat(src.getChildTree()).isNotNull();
        assertThat(entries.findByTree_IdOrderByNameAsc(src.getChildTree().getId()))
                .extracting(TreeEntry::getName).containsExactly("utils");
        assertThat(TreeCanonicalizer.hash(List.of())).isNotBlank();

        commit(owner, repositoryId, "{\"branch\":\"main\",\"message\":\"Delete README\",\"deletedPaths\":[\"README.md\"]}");
        Commit third = main(repositoryId).getHeadCommit();
        assertThat(third.getParentCommit().getCommitHash()).isEqualTo(second.getCommitHash());
        assertThat(entries.findByTree_IdOrderByNameAsc(third.getRootTree().getId()))
                .extracting(TreeEntry::getName).doesNotContain("README.md").contains("keep.txt", "copy.txt", "src");
    }

    @Test
    void rejectsStaleHeadNoOpsInvalidPathsAndDeleteConflictsWithoutMovingBranch() throws Exception {
        Account owner = register("validationowner");
        long repositoryId = repository(owner, "validation", "PUBLIC");
        init(owner, repositoryId);
        String initial = main(repositoryId).getHeadCommit().getCommitHash();
        commit(owner, repositoryId, "{\"branch\":\"main\",\"message\":\"Add one\",\"changes\":[{\"path\":\"a.txt\",\"content\":\"a\"}]}");
        Commit current = main(repositoryId).getHeadCommit();
        String url = commitUrl(repositoryId);
        String stale = "{\"branch\":\"main\",\"expectedHeadHash\":\"" + initial
                + "\",\"message\":\"stale\",\"changes\":[{\"path\":\"b.txt\",\"content\":\"b\"}]}";
        mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict());
        mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"nothing\",\"changes\":[],\"deletedPaths\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"same\",\"changes\":[{\"path\":\"a.txt\",\"content\":\"a\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"conflict\",\"changes\":[{\"path\":\"a.txt\",\"content\":\"b\"}],\"deletedPaths\":[\"a.txt\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"normalized collision\",\"changes\":["
                        + "{\"path\":\"\u00e9.txt\",\"content\":\"one\"},"
                        + "{\"path\":\"e\u0301.txt\",\"content\":\"two\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"bad path\",\"changes\":[{\"path\":\"../escape\",\"content\":\"b\"}]}"))
                .andExpect(status().isBadRequest());
        String tooDeepPath = String.join("/", java.util.Collections.nCopies(65, "x"));
        mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"deep path\",\"changes\":[{\"path\":\""
                        + tooDeepPath + "\",\"content\":\"b\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"missing delete\",\"deletedPaths\":[\"missing\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(main(repositoryId).getHeadCommit().getCommitHash()).isEqualTo(current.getCommitHash());
        assertThat(commits.countByRepository_Id(repositoryId)).isEqualTo(2);
    }

    @Test
    void enforcesAuthenticationOwnerRepositoryAndPrivateVisibility() throws Exception {
        Account owner = register("securityowner");
        Account other = register("securityother");
        long privateId = repository(owner, "private", "PRIVATE");
        init(owner, privateId);
        long otherRepositoryId = repository(other, "foreign-public", "PUBLIC");
        init(other, otherRepositoryId);
        String foreignHead = main(otherRepositoryId).getHeadCommit().getCommitHash();
        String body = "{\"branch\":\"main\",\"message\":\"unauthorized\",\"changes\":[{\"path\":\"x\",\"content\":\"x\"}]}";
        mvc.perform(post(commitUrl(privateId)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(commitUrl(privateId)).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(commitUrl(9223372036854770000L)).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post(commitUrl(privateId)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"absent\",\"message\":\"missing branch\",\"changes\":[{\"path\":\"x\",\"content\":\"x\"}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(commitUrl(privateId)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"bad base64\",\"changes\":[{\"path\":\"x\",\"contentBase64\":\"%%%\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/repositories/" + privateId + "/branches"))
                .andExpect(status().isNotFound());
        mvc.perform(post(commitUrl(privateId)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"expectedHeadHash\":\"" + foreignHead
                        + "\",\"message\":\"foreign head\",\"changes\":[{\"path\":\"x\",\"content\":\"x\"}]}"))
                .andExpect(status().isConflict());
        assertThat(commits.countByRepository_Id(privateId)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithOneExpectedHeadOnlyAdvanceOnce() throws Exception {
        Account owner = register("concurrentcommit");
        long repositoryId = repository(owner, "concurrent", "PUBLIC");
        init(owner, repositoryId);
        String expected = main(repositoryId).getHeadCommit().getCommitHash();
        String url = commitUrl(repositoryId);
        String bodyA = commitBody(expected, "A", "a.txt");
        String bodyB = commitBody(expected, "B", "b.txt");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> performAfter(start, url, owner, bodyA));
            var second = executor.submit(() -> performAfter(start, url, owner, bodyB));
            start.countDown();
            List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS).getResponse().getStatus(),
                    second.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(commits.countByRepository_Id(repositoryId)).isEqualTo(2);
        assertThat(main(repositoryId).getHeadCommit().getParentCommit().getCommitHash()).isEqualTo(expected);
    }

    @Test
    void branchesKeepIndependentHeadsAndHistoriesAfterFeatureCommit() throws Exception {
        Account owner = register("branchisolation");
        long repositoryId = repository(owner, "branch-isolation", "PUBLIC");
        init(owner, repositoryId);
        String initial = main(repositoryId).getHeadCommit().getCommitHash();
        mvc.perform(post("/api/repositories/" + repositoryId + "/branches")
                .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"feature\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.headCommit.commitHash").value(initial));

        String featureBody = "{\"branch\":\"feature\",\"expectedHeadHash\":\"" + initial
                + "\",\"message\":\"Feature only\",\"changes\":[{\"path\":\"feature.txt\",\"content\":\"feature\"}]}";
        String created = mvc.perform(post(commitUrl(repositoryId)).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(featureBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String featureHash = JsonPath.read(created, "$.commitHash");

        assertThat(main(repositoryId).getHeadCommit().getCommitHash()).isEqualTo(initial);
        var featureBranchId = branches.findByRepository_IdAndName(repositoryId, "feature").orElseThrow().getId();
        var featureBranch = branches.findWithDetailsById(featureBranchId).orElseThrow();
        assertThat(featureBranch.getHeadCommit().getCommitHash()).isEqualTo(featureHash);
        assertThat(featureBranch.getHeadCommit().getParentCommit().getCommitHash()).isEqualTo(initial);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/repositories/" + repositoryId + "/commits").param("branch", "feature"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commits.length()").value(2))
                .andExpect(jsonPath("$.commits[0].commitHash").value(featureHash));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/repositories/" + repositoryId + "/commits").param("branch", "main"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commits.length()").value(1))
                .andExpect(jsonPath("$.commits[0].commitHash").value(initial));
        assertThat(commits.findByCommitHash(featureHash).orElseThrow().getRepository().getId()).isEqualTo(repositoryId);
    }

    private MvcResult performAfter(CountDownLatch start, String url, Account owner, String body) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent requests did not start");
        return mvc.perform(post(url).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String commitBody(String expected, String message, String path) {
        return "{\"branch\":\"main\",\"expectedHeadHash\":\"" + expected + "\",\"message\":\"" + message
                + "\",\"changes\":[{\"path\":\"" + path + "\",\"content\":\"" + message + "\"}]}";
    }

    private void commit(Account owner, long repositoryId, String body) throws Exception {
        mvc.perform(post(commitUrl(repositoryId)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private Branch main(long repositoryId) { return branches.findWithDetailsById(branches.findByRepository_IdAndName(repositoryId, "main").orElseThrow().getId()).orElseThrow(); }
    private String commitUrl(long repositoryId) { return "/api/repositories/" + repositoryId + "/commits"; }

    private void init(Account owner, long repositoryId) throws Exception {
        mvc.perform(post("/api/repositories/" + repositoryId + "/git/init").header("Authorization", bearer(owner)))
                .andExpect(status().isCreated());
    }

    private long repository(Account owner, String name, String visibility) throws Exception {
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private Account register(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(response, "$.user.id")).longValue(), JsonPath.read(response, "$.accessToken"));
    }

    private String bearer(Account account) { return "Bearer " + account.token; }
    private record Account(long id, String token) { }
}
