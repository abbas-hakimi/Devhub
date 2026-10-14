package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.RepositoryReleaseRepository;
import com.versioncontrol.repository.RepositoryTagRepository;

@SpringBootTest
@AutoConfigureMockMvc
class RepositoryTagReleaseIntegrationTests {
    private static final AtomicLong NAMES = new AtomicLong();
    @Autowired MockMvc mvc;
    @Autowired CommitRepository commits;
    @Autowired RepositoryTagRepository tags;
    @Autowired RepositoryReleaseRepository releases;

    @Test
    void createsListsGetsAndDeletesImmutableCommitTags() throws Exception {
        Account owner = register("tagowner");
        Account writer = register("tagwriter");
        Account reader = register("tagreader");
        long repositoryId = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repositoryId);
        String commitHash = commit(owner, repositoryId, initial);
        acceptInvite(owner, writer, repositoryId, "WRITE");
        acceptInvite(owner, reader, repositoryId, "READ");

        mvc.perform(post(tagPath(repositoryId)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("v1.0", commitHash)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("v1.0"))
                .andExpect(jsonPath("$.commitHash").value(commitHash)).andExpect(jsonPath("$.createdAt").exists());
        mvc.perform(get(tagPath(repositoryId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(tagPath(repositoryId)).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].commitHash").value(commitHash));
        mvc.perform(get(tagPath(repositoryId) + "/v1.0").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("v1.0"));
        mvc.perform(put(tagPath(repositoryId) + "/v1.0").header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(tagPath(repositoryId)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("v1.0", commitHash)))
                .andExpect(status().isConflict());
        mvc.perform(delete(tagPath(repositoryId) + "/v1.0").header("Authorization", bearer(writer)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(tagPath(repositoryId) + "/v1.0").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(commits.findByCommitHash(commitHash)).isPresent();
    }

    @Test
    void tagValidationScopeAndPaginationAreEnforced() throws Exception {
        Account owner = register("tagvalidationowner");
        long firstRepository = createRepository(owner, "PUBLIC");
        long secondRepository = createRepository(owner, "PUBLIC");
        String firstCommit = initialize(owner, firstRepository);
        String secondCommit = initialize(owner, secondRepository);
        mvc.perform(post(tagPath(firstRepository)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("../secret", firstCommit)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(tagPath(firstRepository)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("bad name", firstCommit)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(tagPath(firstRepository)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("bad", "f".repeat(64))))
                .andExpect(status().isNotFound());
        mvc.perform(post(tagPath(firstRepository)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("cross-repository", secondCommit)))
                .andExpect(status().isNotFound());
        mvc.perform(post(tagPath(firstRepository)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("v1", firstCommit))).andExpect(status().isCreated());
        mvc.perform(post(tagPath(firstRepository)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("v1", firstCommit))).andExpect(status().isConflict());
        mvc.perform(post(tagPath(secondRepository)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("v1", secondCommit))).andExpect(status().isCreated());
        mvc.perform(get(tagPath(firstRepository)).param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("v1"));
        mvc.perform(get(tagPath(firstRepository)).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(tagPath(firstRepository) + "/missing")).andExpect(status().isNotFound());
        mvc.perform(delete(tagPath(firstRepository) + "/v1").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(get(tagPath(firstRepository) + "/v1")).andExpect(status().isNotFound());
    }

    @Test
    void releaseDraftPublishingVisibilityAndTagReferenceLifecycle() throws Exception {
        Account owner = register("releaseowner");
        Account writer = register("releasewriter");
        Account reader = register("releasereader");
        long repositoryId = createRepository(owner, "PUBLIC");
        String commitHash = initialize(owner, repositoryId);
        createTag(owner, repositoryId, "v2.0", commitHash);
        acceptInvite(owner, writer, repositoryId, "WRITE");
        acceptInvite(owner, reader, repositoryId, "READ");

        mvc.perform(post(releasePath(repositoryId)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("v2.0", "Release 2", true, false)))
                .andExpect(status().isForbidden());
        String draftJson = mvc.perform(post(releasePath(repositoryId)).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody("v2.0", "Release 2", true, false)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.draft").value(true))
                .andExpect(jsonPath("$.publishedAt").doesNotExist()).andReturn().getResponse().getContentAsString();
        long releaseId = ((Number) JsonPath.read(draftJson, "$.id")).longValue();

        mvc.perform(get(releasePath(repositoryId))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(releasePath(repositoryId) + "/" + releaseId)).andExpect(status().isNotFound());
        mvc.perform(get(releasePath(repositoryId) + "/" + releaseId).header("Authorization", bearer(reader)))
                .andExpect(status().isNotFound());
        mvc.perform(get(releasePath(repositoryId) + "/" + releaseId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.draft").value(true));
        mvc.perform(delete(tagPath(repositoryId) + "/v2.0").header("Authorization", bearer(owner)))
                .andExpect(status().isConflict());
        mvc.perform(post(releasePath(repositoryId)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("v2.0", "Duplicate", true, false)))
                .andExpect(status().isConflict());

        String published = mvc.perform(patch(releasePath(repositoryId) + "/" + releaseId)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.draft").value(false))
                .andExpect(jsonPath("$.publishedAt").exists()).andReturn().getResponse().getContentAsString();
        String publishedAt = JsonPath.read(published, "$.publishedAt");
        mvc.perform(get(releasePath(repositoryId))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(releasePath(repositoryId) + "/" + releaseId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tagName").value("v2.0"));
        mvc.perform(get(releasePath(repositoryId) + "/" + releaseId).header("Authorization", bearer(reader)))
                .andExpect(status().isOk());
        mvc.perform(patch(releasePath(repositoryId) + "/" + releaseId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Release 2.1\",\"description\":\"Details\",\"prerelease\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Release 2.1"))
                .andExpect(jsonPath("$.prerelease").value(true)).andExpect(jsonPath("$.publishedAt").value(publishedAt));
        mvc.perform(patch(releasePath(repositoryId) + "/" + releaseId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"draft\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.draft").value(true))
                .andExpect(jsonPath("$.publishedAt").doesNotExist());
        mvc.perform(get(releasePath(repositoryId))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(patch(releasePath(repositoryId) + "/" + releaseId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"draft\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(releasePath(repositoryId) + "/" + releaseId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(releasePath(repositoryId) + "/" + releaseId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(delete(tagPath(repositoryId) + "/v2.0").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(commits.findByCommitHash(commitHash)).isPresent();
    }

    @Test
    void privateRepositoryIsolationAndConcurrentTagAndReleaseCreation() throws Exception {
        Account owner = register("releaseconcurrentowner");
        Account outsider = register("releaseoutsider");
        long privateRepository = createRepository(owner, "PRIVATE");
        String commitHash = initialize(owner, privateRepository);
        mvc.perform(get(tagPath(privateRepository))).andExpect(status().isNotFound());
        mvc.perform(get(releasePath(privateRepository))).andExpect(status().isNotFound());
        mvc.perform(post(tagPath(privateRepository)).header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content(tagBody("denied", commitHash)))
                .andExpect(status().isNotFound());
        mvc.perform(post(tagPath(privateRepository)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody("anonymous", commitHash))).andExpect(status().isUnauthorized());

        String concurrentTag = "race-" + NAMES.incrementAndGet();
        CountDownLatch tagStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createTagAtBarrier(tagStart, owner, privateRepository, concurrentTag, commitHash));
            var second = executor.submit(() -> createTagAtBarrier(tagStart, owner, privateRepository, concurrentTag, commitHash));
            tagStart.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }

        String concurrentReleaseTag = "release-race-" + NAMES.incrementAndGet();
        createTag(owner, privateRepository, concurrentReleaseTag, commitHash);
        CountDownLatch releaseStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createReleaseAtBarrier(releaseStart, owner, privateRepository, concurrentReleaseTag));
            var second = executor.submit(() -> createReleaseAtBarrier(releaseStart, owner, privateRepository, concurrentReleaseTag));
            releaseStart.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(tags.count()).isGreaterThanOrEqualTo(2);
        assertThat(releases.count()).isGreaterThanOrEqualTo(1);
    }

    private int createTagAtBarrier(CountDownLatch start, Account owner, long repositoryId, String name, String commitHash) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("tag race did not start");
        return mvc.perform(post(tagPath(repositoryId)).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(tagBody(name, commitHash)))
                .andReturn().getResponse().getStatus();
    }

    private int createReleaseAtBarrier(CountDownLatch start, Account owner, long repositoryId, String tagName) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release race did not start");
        return mvc.perform(post(releasePath(repositoryId)).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody(tagName, "Concurrent release", true, false)))
                .andReturn().getResponse().getStatus();
    }

    private void createTag(Account owner, long repositoryId, String name, String commitHash) throws Exception {
        mvc.perform(post(tagPath(repositoryId)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(tagBody(name, commitHash))).andExpect(status().isCreated());
    }

    private void acceptInvite(Account owner, Account invitee, long repositoryId, String role) throws Exception {
        String response = mvc.perform(post("/api/repositories/{id}/collaborators/invitations", repositoryId)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + invitee.username() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long invitationId = ((Number) JsonPath.read(response, "$.invitationId")).longValue();
        mvc.perform(post("/api/collaboration-invitations/{id}/accept", invitationId)
                        .header("Authorization", bearer(invitee))).andExpect(status().isOk());
    }

    private String commit(Account owner, long repositoryId, String parentHash) throws Exception {
        String response = mvc.perform(post("/api/repositories/{id}/commits", repositoryId)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"main\",\"expectedHeadHash\":\"" + parentHash
                                + "\",\"message\":\"tagged commit\",\"changes\":[{\"path\":\"file.txt\",\"content\":\"content\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.commitHash");
    }

    private String initialize(Account owner, long repositoryId) throws Exception {
        String response = mvc.perform(post("/api/repositories/{id}/git/init", repositoryId)
                        .header("Authorization", bearer(owner))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.commitHash");
    }

    private long createRepository(Account owner, String visibility) throws Exception {
        String name = "tag-release-" + NAMES.incrementAndGet() + "-" + System.nanoTime();
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private Account register(String prefix) throws Exception {
        String username = prefix + NAMES.incrementAndGet() + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(username, JsonPath.read(response, "$.accessToken"));
    }

    private String bearer(Account account) { return "Bearer " + account.token(); }
    private String tagPath(long repositoryId) { return "/api/repositories/" + repositoryId + "/tags"; }
    private String releasePath(long repositoryId) { return "/api/repositories/" + repositoryId + "/releases"; }
    private String tagBody(String name, String hash) { return "{\"name\":\"" + name + "\",\"commitHash\":\"" + hash + "\"}"; }
    private String releaseBody(String tag, String title, boolean draft, boolean prerelease) {
        return "{\"tagName\":\"" + tag + "\",\"title\":\"" + title + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease + "}";
    }
    private record Account(String username, String token) { }
}
