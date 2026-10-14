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
import com.versioncontrol.repository.DiscussionReactionRepository;
import com.versioncontrol.repository.DiscussionReplyReactionRepository;
import com.versioncontrol.repository.RepositoryDiscussionRepository;

@SpringBootTest
@AutoConfigureMockMvc
class RepositoryDiscussionIntegrationTests {
    private static final AtomicLong IDS = new AtomicLong();
    @Autowired MockMvc mvc;
    @Autowired RepositoryDiscussionRepository discussions;
    @Autowired DiscussionReactionRepository reactions;
    @Autowired DiscussionReplyReactionRepository replyReactions;

    @Test
    void discussionLifecycleCategoriesPermissionsRepliesAndAcceptedAnswers() throws Exception {
        Account owner = register("discussionowner");
        Account writer = register("discussionwriter");
        Account reader = register("discussionreader");
        Account outsider = register("discussionoutsider");
        long repo = createRepository(owner, "PUBLIC");
        invite(owner, writer, repo, "WRITE");
        invite(owner, reader, repo, "READ");

        mvc.perform(get(discussionPath(repo))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post(discussionPath(repo)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("GENERAL", "anon", "body"))).andExpect(status().isUnauthorized());
        mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(reader)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("GENERAL", "reader", "body"))).andExpect(status().isForbidden());
        mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("ANNOUNCEMENTS", "writer announcement", "body"))).andExpect(status().isForbidden());
        mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("INVALID", "invalid category", "body"))).andExpect(status().isBadRequest());

        String generalJson = mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content(discussionBody("GENERAL", "General topic", "Markdown **body**")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.category").value("GENERAL")).andExpect(jsonPath("$.author.username").value(writer.username()))
                .andReturn().getResponse().getContentAsString();
        long generalId = id(generalJson);
        mvc.perform(get("/api/discussions/" + generalId)).andExpect(status().isOk()).andExpect(jsonPath("$.body").value("Markdown **body**"));
        mvc.perform(post("/api/discussions/" + generalId + "/reactions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"HEART\"}")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/discussions/" + generalId).header("Authorization", bearer(outsider))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"no\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/discussions/" + generalId).header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Edited\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Edited"));
        mvc.perform(patch("/api/discussions/" + generalId).header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/discussions/" + generalId + "/close").header("Authorization", bearer(writer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CLOSED")).andExpect(jsonPath("$.closedAt").exists());
        mvc.perform(post("/api/discussions/" + generalId + "/replies").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"blocked\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/discussions/" + generalId + "/reopen").header("Authorization", bearer(writer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN")).andExpect(jsonPath("$.closedAt").doesNotExist());

        String qaJson = mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                        .content(discussionBody("Q_AND_A", "Question", "Question body")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(2)).andReturn().getResponse().getContentAsString();
        long qaId = id(qaJson);
        long rootReply = createReply(writer, qaId, "Answer", null);
        long childReply = createReply(writer, qaId, "Follow up", rootReply);
        mvc.perform(post("/api/discussions/" + qaId + "/replies").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content(replyBody("Missing parent", Long.MAX_VALUE))).andExpect(status().isNotFound());
        mvc.perform(post("/api/discussions/" + qaId + "/replies").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content(replyBody("Too deep", childReply))).andExpect(status().isBadRequest());
        long otherDiscussion = id(mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("GENERAL", "Other", "body"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long otherReply = createReply(owner, otherDiscussion, "Other reply", null);
        mvc.perform(post("/api/discussions/" + qaId + "/replies").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content(replyBody("Wrong parent", otherReply))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/discussions/" + qaId + "/replies").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/discussions/" + qaId + "/replies").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/discussion-replies/" + rootReply).header("Authorization", bearer(writer))).andExpect(status().isConflict());
        mvc.perform(put("/api/discussions/" + qaId + "/accepted-answer").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"replyId\":" + rootReply + "}" )).andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/" + qaId)).andExpect(status().isOk()).andExpect(jsonPath("$.acceptedAnswerReplyId").value(rootReply));
        mvc.perform(delete("/api/discussion-replies/" + rootReply).header("Authorization", bearer(writer))).andExpect(status().isConflict());
        mvc.perform(delete("/api/discussion-replies/" + childReply).header("Authorization", bearer(writer))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/discussion-replies/" + rootReply).header("Authorization", bearer(writer))).andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/" + qaId)).andExpect(status().isOk()).andExpect(jsonPath("$.acceptedAnswerReplyId").doesNotExist());
        mvc.perform(post("/api/discussion-replies/" + rootReply + "/reactions").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HEART\"}")).andExpect(status().isNotFound());

        mvc.perform(put("/api/discussions/" + qaId + "/accepted-answer").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"replyId\":" + otherReply + "}" )).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/discussions/" + qaId).header("Authorization", bearer(writer))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/discussions/" + qaId).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/" + qaId)).andExpect(status().isNotFound());
        mvc.perform(get(discussionPath(repo)).param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test
    void reactionsAreIdempotentScopedAndReadersCanReact() throws Exception {
        Account owner = register("reactionowner"); Account writer = register("reactionwriter"); Account reader = register("reactionreader");
        long repo = createRepository(owner, "PUBLIC"); invite(owner, writer, repo, "WRITE"); invite(owner, reader, repo, "READ");
        long discussionId = id(mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("IDEAS", "Idea", "Body"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long replyId = createReply(writer, discussionId, "Reply", null);
        mvc.perform(post("/api/discussions/" + discussionId + "/reactions").header("Authorization", bearer(reader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HEART\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/discussions/" + discussionId + "/reactions").header("Authorization", bearer(reader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HEART\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/" + discussionId)).andExpect(status().isOk()).andExpect(jsonPath("$.reactions.HEART").value(1));
        mvc.perform(post("/api/discussion-replies/" + replyId + "/reactions").header("Authorization", bearer(reader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"ROCKET\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/" + discussionId + "/replies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reactions.ROCKET").value(1));
        mvc.perform(delete("/api/discussions/" + discussionId + "/reactions").param("type", "HEART")
                .header("Authorization", bearer(writer))).andExpect(status().isNoContent());
        mvc.perform(get("/api/discussions/" + discussionId)).andExpect(status().isOk()).andExpect(jsonPath("$.reactions.HEART").value(1));
        mvc.perform(delete("/api/discussions/" + discussionId + "/reactions").param("type", "HEART")
                .header("Authorization", bearer(reader))).andExpect(status().isNoContent());
        mvc.perform(post("/api/discussion-replies/" + replyId + "/reactions").header("Authorization", bearer(reader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"INVALID\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/discussion-replies/" + replyId).header("Authorization", bearer(writer))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/discussions/" + discussionId).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        assertThat(reactions.countByDiscussion_Id(discussionId)).isZero();
        assertThat(replyReactions.countByReply_Id(replyId)).isZero();
    }

    @Test
    void privateIsolationAnnouncementRulesAndRoleDowngradeApplyImmediately() throws Exception {
        Account owner = register("private-discussion-owner"); Account writer = register("private-discussion-writer"); Account outsider = register("private-discussion-outsider");
        long repo = createRepository(owner, "PRIVATE"); invite(owner, writer, repo, "WRITE");
        mvc.perform(get(discussionPath(repo))).andExpect(status().isNotFound());
        mvc.perform(get(discussionPath(repo)).header("Authorization", bearer(outsider))).andExpect(status().isNotFound());
        mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("ANNOUNCEMENTS", "Denied", "Body"))).andExpect(status().isForbidden());
        String json = mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("ANNOUNCEMENTS", "Notice", "Body"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long discussionId = id(json);
        mvc.perform(get("/api/discussions/" + discussionId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/discussions/" + discussionId).header("Authorization", bearer(writer))).andExpect(status().isOk());
        mvc.perform(post("/api/discussions/" + discussionId + "/replies").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"allowed before downgrade\"}")).andExpect(status().isCreated());
        mvc.perform(put("/api/repositories/" + repo + "/collaborators/" + writer.id() + "/role").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"READ\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/discussions/" + discussionId + "/replies").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"denied after downgrade\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/discussions/" + discussionId + "/reactions").header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"THUMBS_UP\"}")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/discussions/" + discussionId).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
    }

    @Test
    void concurrentNumberingAndReactionAdditionDoNotDuplicate() throws Exception {
        Account owner = register("concurrent-discussion-owner");
        long repo = createRepository(owner, "PUBLIC");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAtBarrier(start, owner, repo, "Concurrent 1"));
            var second = executor.submit(() -> createAtBarrier(start, owner, repo, "Concurrent 2"));
            start.countDown();
            List<Long> numbers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(numbers).containsExactlyInAnyOrder(1L, 2L);
        }
        long discussionId = discussions.findAll().stream().filter(d -> d.getRepository().getId().equals(repo)).findFirst().orElseThrow().getId();
        CountDownLatch reactStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reactAtBarrier(reactStart, owner, discussionId));
            var second = executor.submit(() -> reactAtBarrier(reactStart, owner, discussionId));
            reactStart.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsOnly(204);
        }
        assertThat(reactions.count()).isEqualTo(1);
    }

    private long createAtBarrier(CountDownLatch start, Account owner, long repo, String title) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("creation race did not start");
        String response = mvc.perform(post(discussionPath(repo)).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(discussionBody("GENERAL", title, "body"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.number")).longValue();
    }
    private int reactAtBarrier(CountDownLatch start, Account user, long discussionId) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("reaction race did not start");
        return mvc.perform(post("/api/discussions/" + discussionId + "/reactions").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CELEBRATE\"}")).andReturn().getResponse().getStatus();
    }
    private long createReply(Account user, long discussionId, String body, Long parent) throws Exception {
        String json = mvc.perform(post("/api/discussions/" + discussionId + "/replies").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(replyBody(body, parent))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return id(json);
    }
    private String replyBody(String body, Long parent) {
        return "{\"body\":\"" + body + "\"" + (parent == null ? "" : ",\"parentReplyId\":" + parent) + "}";
    }
    private void invite(Account owner, Account invitee, long repositoryId, String role) throws Exception {
        String response = mvc.perform(post("/api/repositories/" + repositoryId + "/collaborators/invitations").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + invitee.username() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long invitationId = ((Number) JsonPath.read(response, "$.invitationId")).longValue();
        mvc.perform(post("/api/collaboration-invitations/" + invitationId + "/accept").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk());
    }
    private long createRepository(Account owner, String visibility) throws Exception {
        String name = "discussion-" + IDS.incrementAndGet() + "-" + System.nanoTime();
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
    private Account register(String prefix) throws Exception {
        String username = prefix.replace('-', '_') + IDS.incrementAndGet() + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(username, JsonPath.read(response, "$.accessToken"), ((Number) JsonPath.read(response, "$.user.id")).longValue());
    }
    private long id(String json) { return ((Number) JsonPath.read(json, "$.id")).longValue(); }
    private String bearer(Account account) { return "Bearer " + account.token(); }
    private String discussionPath(long repo) { return "/api/repositories/" + repo + "/discussions"; }
    private String discussionBody(String category, String title, String body) { return "{\"category\":\"" + category + "\",\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"; }
    private record Account(String username, String token, long id) { }
}
