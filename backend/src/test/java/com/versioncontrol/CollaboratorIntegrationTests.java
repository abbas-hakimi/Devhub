package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.dto.collaborator.CreateInvitationRequest;
import com.versioncontrol.entity.CollaboratorRole;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryVisibility;
import com.versioncontrol.entity.User;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.UserRepository;
import com.versioncontrol.service.CollaboratorService;

@SpringBootTest
@AutoConfigureMockMvc
class CollaboratorIntegrationTests {
    private static final AtomicLong SEQ = new AtomicLong();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CollaboratorService collaboratorService;
    @Autowired RepositoryRepository repositories;
    @Autowired UserRepository users;

    @Test
    void flywayV7CreatesCollaboratorTableAndConstraints() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '7'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name) = 'repository_collaborators'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_name = 'repository_collaborators' and constraint_type = 'UNIQUE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void privateWriteCollaboratorCanContributeCreatePrAndBeReviewedThenDowngradeAndRemovalRevokeWrites() throws Exception {
        Account owner = register("collabowner");
        Account writer = register("collabwriter");
        long repo = createRepository(owner, "PRIVATE");
        String initial = initialize(owner, repo);
        String base = "/api/repositories/" + repo;
        String invitation = mvc.perform(post(base + "/collaborators/invitations").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + writer.username() + "\",\"role\":\"WRITE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("WRITE"))
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getContentAsString();
        long inviteId = ((Number) JsonPath.read(invitation, "$.invitationId")).longValue();

        mvc.perform(get("/api/repositories/{id}", repo)).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/{id}", repo).header("Authorization", bearer(writer))).andExpect(status().isNotFound());
        mvc.perform(get("/api/me/collaboration-invitations").header("Authorization", bearer(writer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].invitationId").value(inviteId));
        mvc.perform(post("/api/collaboration-invitations/{id}/accept", inviteId).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/collaboration-invitations/{id}/accept", inviteId).header("Authorization", bearer(writer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(get("/api/repositories/{id}", repo).header("Authorization", bearer(writer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PRIVATE"));

        mvc.perform(post(base + "/branches").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"feature/collab\",\"fromCommitHash\":\"" + initial + "\"}"))
                .andExpect(status().isCreated());
        String featureHead = createCommit(writer, repo, "feature/collab", initial, "feature", "feature.txt");
        createCommit(owner, repo, "main", initial, "base change", "base.txt");
        String prJson = mvc.perform(post(base + "/pull-requests").header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Contributor PR\",\"sourceBranch\":\"feature/collab\",\"targetBranch\":\"main\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.author.id").value(writer.id()))
                .andExpect(jsonPath("$.author.username").value(writer.username())).andReturn().getResponse().getContentAsString();
        long prId = ((Number) JsonPath.read(prJson, "$.id")).longValue();
        mvc.perform(post("/api/pull-requests/{id}/reviews", prId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"APPROVED\",\"body\":\"Looks good\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reviewer.id").value(owner.id()));
        mvc.perform(post("/api/pull-requests/{id}/reviews", prId).header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"APPROVED\",\"body\":\"self approve\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/pull-requests/{id}/review-status", prId).header("Authorization", bearer(writer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentApprovals").value(1));
        mvc.perform(post("/api/pull-requests/{id}/close", prId).header("Authorization", bearer(owner))).andExpect(status().isOk());
        mvc.perform(post("/api/pull-requests/{id}/reopen", prId).header("Authorization", bearer(writer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));

        String issueJson = mvc.perform(post("/api/repositories/{id}/issues", repo).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Maintainer issue\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long issueId = ((Number) JsonPath.read(issueJson, "$.id")).longValue();
        mvc.perform(put("/api/issues/{id}", issueId).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(put(base + "/collaborators/{userId}/role", writer.id()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"READ\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("READ"));
        mvc.perform(get("/api/repositories/{id}/branches", repo).header("Authorization", bearer(writer))).andExpect(status().isOk());
        mvc.perform(post(base + "/branches").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"feature/blocked\",\"fromCommitHash\":\"" + featureHead + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/repositories/{id}/issues", repo).header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Read-only issue\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/issues/{id}/comments", issueId).header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Read only\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(base + "/collaborators/invitations").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + register("collabother").username() + "\",\"role\":\"WRITE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(base + "/collaborators/{userId}", writer.id()).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/repositories/{id}", repo).header("Authorization", bearer(writer))).andExpect(status().isNotFound());
        mvc.perform(post(base + "/branches").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"feature/revoked\",\"fromCommitHash\":\"" + featureHead + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invitationAuthorizationDuplicateDeclineAndOwnerOnlyMaintainGrant() throws Exception {
        Account owner = register("collabinviteowner");
        Account maintainer = register("collabmaintainer");
        Account invitee = register("collabdeclinee");
        long repo = createRepository(owner, "PUBLIC");
        String base = "/api/repositories/" + repo;
        mvc.perform(post(base + "/collaborators/invitations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + invitee.username() + "\",\"role\":\"READ\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/collaborators/invitations").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + owner.username() + "\",\"role\":\"WRITE\"}"))
                .andExpect(status().isConflict());
        String maintainInvitation = invite(owner, repo, maintainer, "MAINTAIN");
        long maintainId = ((Number) JsonPath.read(maintainInvitation, "$.invitationId")).longValue();
        mvc.perform(post("/api/collaboration-invitations/{id}/accept", maintainId).header("Authorization", bearer(maintainer)))
                .andExpect(status().isOk());
        mvc.perform(post(base + "/collaborators/invitations").header("Authorization", bearer(maintainer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + invitee.username() + "\",\"role\":\"MAINTAIN\"}"))
                .andExpect(status().isForbidden());
        String pending = invite(owner, repo, invitee, "WRITE");
        long id = ((Number) JsonPath.read(pending, "$.invitationId")).longValue();
        mvc.perform(post(base + "/collaborators/invitations").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + invitee.username() + "\",\"role\":\"WRITE\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/collaboration-invitations/{id}/decline", id).header("Authorization", bearer(invitee)))
                .andExpect(status().isNoContent());
        String reinvited = invite(owner, repo, invitee, "READ");
        assertThat(((Number) JsonPath.read(reinvited, "$.invitationId")).longValue()).isNotEqualTo(id);
        mvc.perform(get(base + "/collaborators").header("Authorization", bearer(maintainer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].username").value(maintainer.username()));
    }

    @Test
    void concurrentInvitationsAndAcceptancesAreSerialized() throws Exception {
        String suffix = Long.toString(SEQ.incrementAndGet()) + System.nanoTime();
        User owner = users.saveAndFlush(new User("raceowner" + suffix, "raceowner" + suffix + "@example.test", "hash"));
        User invitee = users.saveAndFlush(new User("raceinvitee" + suffix, "raceinvitee" + suffix + "@example.test", "hash"));
        Repository repository = repositories.saveAndFlush(new Repository("race-repo-" + suffix, null, RepositoryVisibility.PUBLIC, owner));
        CountDownLatch inviteStart = new CountDownLatch(1);
        var invitePool = Executors.newFixedThreadPool(2);
        try {
            var first = invitePool.submit(() -> inviteAtBarrier(inviteStart, repository.getId(), invitee.getUsername(), owner.getId()));
            var second = invitePool.submit(() -> inviteAtBarrier(inviteStart, repository.getId(), invitee.getUsername(), owner.getId()));
            inviteStart.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "CONFLICT");
        } finally {
            invitePool.shutdownNow();
        }
        Long invitationId = jdbc.queryForObject("select id from repository_collaborators where repository_id = ? and user_id = ?",
                Long.class, repository.getId(), invitee.getId());
        CountDownLatch acceptStart = new CountDownLatch(1);
        var acceptPool = Executors.newFixedThreadPool(2);
        try {
            var first = acceptPool.submit(() -> acceptAtBarrier(acceptStart, invitationId, invitee.getId()));
            var second = acceptPool.submit(() -> acceptAtBarrier(acceptStart, invitationId, invitee.getId()));
            acceptStart.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "CONFLICT");
        } finally {
            acceptPool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select status from repository_collaborators where id = ?", String.class, invitationId))
                .isEqualTo("ACCEPTED");
    }

    private String invite(Account owner, long repo, Account invitee, String role) throws Exception {
        return mvc.perform(post("/api/repositories/{id}/collaborators/invitations", repo).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + invitee.username() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
    private long createRepository(Account account, String visibility) throws Exception {
        String name = "collab-repo-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        String json = mvc.perform(post("/api/repositories").header("Authorization", bearer(account)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
    private String initialize(Account account, long repo) throws Exception {
        String json = mvc.perform(post("/api/repositories/{id}/git/init", repo).header("Authorization", bearer(account)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.commitHash");
    }
    private String createCommit(Account account, long repo, String branch, String expected, String message, String path) throws Exception {
        String json = mvc.perform(post("/api/repositories/{id}/commits", repo).header("Authorization", bearer(account))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"" + branch + "\",\"expectedHeadHash\":\"" + expected
                                + "\",\"message\":\"" + message + "\",\"changes\":[{\"path\":\"" + path + "\",\"content\":\"" + message + "\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.commitHash");
    }
    private Account register(String prefix) throws Exception {
        String username = prefix + SEQ.incrementAndGet() + System.nanoTime();
        String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(json, "$.user.id")).longValue(), username, JsonPath.read(json, "$.accessToken"));
    }
    private String bearer(Account a) { return "Bearer " + a.token(); }
    private String inviteAtBarrier(CountDownLatch start, Long repositoryId, String username, Long ownerId) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("invite operations did not reach barrier");
        try {
            collaboratorService.invite(repositoryId, new CreateInvitationRequest(username, CollaboratorRole.WRITE), ownerId);
            return "CREATED";
        } catch (RepositoryConflictException conflict) { return "CONFLICT"; }
    }
    private String acceptAtBarrier(CountDownLatch start, Long invitationId, Long userId) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("accept operations did not reach barrier");
        try {
            collaboratorService.accept(invitationId, userId);
            return "ACCEPTED";
        } catch (RepositoryConflictException conflict) { return "CONFLICT"; }
    }
    private record Account(Long id, String username, String token) { }
}
