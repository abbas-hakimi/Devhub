package com.versioncontrol;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class IssueCollaborationIntegrationTests {
    @Autowired MockMvc mvc;

    @Test void publicIssueLifecycleEnforcesRolesAndStateTransitions() throws Exception {
        Account owner = account("issueowner");
        Account author = account("issueauthor");
        Account outsider = account("issueoutsider");
        long repositoryId = repository(owner, false);
        String issueBody = "{\"title\":\"Initial issue\",\"description\":\"Details\",\"authorId\":" + owner.id + "}";
        mvc.perform(post("/api/repositories/" + repositoryId + "/issues").contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isUnauthorized());
        String created = mvc.perform(post("/api/repositories/" + repositoryId + "/issues").header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content(issueBody)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.id").value(author.id)).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn().getResponse().getContentAsString();
        long issueId = id(created);
        mvc.perform(get("/api/issues/" + issueId)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Initial issue"));
        mvc.perform(get("/api/repositories/" + repositoryId + "/issues").param("status", "OPEN")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Initial issue")));
        mvc.perform(get("/api/repositories/" + repositoryId + "/issues").param("status", "CLOSED")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Initial issue"))));
        mvc.perform(get("/api/repositories/" + repositoryId + "/issues").param("status", "INVALID")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Edited by author\",\"description\":\"Revised details\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Edited by author"));
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(outsider)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Hijacked\"}" )).andExpect(status().isForbidden());
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\":" + owner.id + "}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\",\"assigneeId\":" + owner.id + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closedAt").isNotEmpty()).andExpect(jsonPath("$.assignee.id").value(owner.id));
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\"}" )).andExpect(status().isOk()).andExpect(jsonPath("$.closedAt").doesNotExist());
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\":" + outsider.id + "}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/issues/" + issueId).header("Authorization", bearer(outsider))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/issues/" + issueId).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/issues/" + issueId)).andExpect(status().isNotFound());
    }

    @Test void privateIssuesAreHiddenAndOnlyOwnerCanCreateOrReadThem() throws Exception {
        Account owner = account("privateowner");
        Account outsider = account("privateoutsider");
        long repositoryId = repository(owner, true);
        mvc.perform(post("/api/repositories/" + repositoryId + "/issues").header("Authorization", bearer(outsider))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"not allowed\"}"))
                .andExpect(status().isNotFound());
        String response = mvc.perform(post("/api/repositories/" + repositoryId + "/issues").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"private ticket\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long issueId = id(response);
        mvc.perform(get("/api/repositories/" + repositoryId + "/issues")).andExpect(status().isNotFound());
        mvc.perform(get("/api/issues/" + issueId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/issues/" + issueId).header("Authorization", bearer(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/issues/" + issueId).header("Authorization", bearer(owner))).andExpect(status().isOk());
        mvc.perform(get("/api/repositories/" + repositoryId + "/issues").header("Authorization", bearer(owner))).andExpect(status().isOk());
    }

    @Test void commentsEnforceRepositoryVisibilityAndAuthorOrOwnerModeration() throws Exception {
        Account owner = account("commentowner");
        Account author = account("commentauthor");
        Account outsider = account("commentoutsider");
        long repositoryId = repository(owner, false);
        long privateRepoId = repository(owner, true);
        long issueId = createIssue(repositoryId, author, "commentable");
        long privateIssueId = createIssue(privateRepoId, owner, "private-commentable");
        mvc.perform(post("/api/issues/" + issueId + "/comments").header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"   \"}")).andExpect(status().isBadRequest());
        String commentResponse = mvc.perform(post("/api/issues/" + issueId + "/comments").header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"first comment\",\"authorId\":" + owner.id + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.author.id").value(author.id)).andReturn().getResponse().getContentAsString();
        long commentId = id(commentResponse);
        mvc.perform(put("/api/issues/comments/" + commentId).header("Authorization", bearer(outsider)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hijack\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/issues/comments/" + commentId).header("Authorization", bearer(outsider))).andExpect(status().isForbidden());
        mvc.perform(put("/api/issues/comments/" + commentId).header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"edited comment\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.body").value("edited comment"));
        mvc.perform(post("/api/issues/" + privateIssueId + "/comments").header("Authorization", bearer(outsider)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"blocked\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/issues/" + privateIssueId + "/comments")).andExpect(status().isNotFound());
        String moderated = mvc.perform(post("/api/issues/" + issueId + "/comments").header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"owner may moderate\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long moderatedId = id(moderated);
        mvc.perform(put("/api/issues/comments/" + moderatedId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"moderated\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/issues/comments/" + commentId).header("Authorization", bearer(author))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/issues/comments/" + moderatedId).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
    }

    @Test void labelsAreScopedValidatedAndCannotCrossRepositoryBoundaries() throws Exception {
        Account owner = account("labelowner");
        Account outsider = account("labeloutsider");
        long repoA = repository(owner, false);
        long repoB = repository(owner, false);
        long issueA = createIssue(repoA, owner, "issue-a");
        long issueB = createIssue(repoB, owner, "issue-b");
        mvc.perform(post("/api/repositories/" + repoA + "/labels").header("Authorization", bearer(outsider)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bug\",\"color\":\"#FF0000\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/repositories/" + repoA + "/labels").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad-color\",\"color\":\"red\"}")).andExpect(status().isBadRequest());
        String response = mvc.perform(post("/api/repositories/" + repoA + "/labels").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  bug  \",\"description\":\"An issue\",\"color\":\"#ff0000\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("bug")).andExpect(jsonPath("$.color").value("#FF0000"))
                .andReturn().getResponse().getContentAsString();
        long labelA = id(response);
        String labelBResponse = mvc.perform(post("/api/repositories/" + repoB + "/labels").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bug\",\"color\":\"#00AA00\"}" )).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long labelB = id(labelBResponse);
        mvc.perform(post("/api/repositories/" + repoA + "/labels").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bug\",\"color\":\"#000000\"}" )).andExpect(status().isConflict());
        mvc.perform(put("/api/labels/" + labelA).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"defect\",\"description\":\"Updated\",\"color\":\"#123abc\"}" ))
                .andExpect(status().isOk()).andExpect(jsonPath("$.color").value("#123ABC"));
        mvc.perform(put("/api/labels/" + labelA).header("Authorization", bearer(outsider)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"nope\",\"color\":\"#123456\"}" )).andExpect(status().isForbidden());
        mvc.perform(delete("/api/labels/" + labelA).header("Authorization", bearer(outsider))).andExpect(status().isForbidden());
        mvc.perform(post("/api/issues/" + issueA + "/labels/" + labelA).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(post("/api/issues/" + issueA + "/labels/" + labelA).header("Authorization", bearer(owner))).andExpect(status().isConflict());
        mvc.perform(post("/api/issues/" + issueA + "/labels/" + labelB).header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/issues/" + issueA)).andExpect(status().isOk()).andExpect(jsonPath("$.labels[0].name").value("defect"));
        mvc.perform(delete("/api/issues/" + issueA + "/labels/" + labelA).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/labels/" + labelA).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/labels/" + labelB).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/issues/" + issueB)).andExpect(status().isOk());
    }

    private long repository(Account owner, boolean privateRepo) throws Exception {
        String body = "{\"name\":\"repo-" + System.nanoTime() + "\",\"visibility\":\"" + (privateRepo ? "PRIVATE" : "PUBLIC") + "\"}";
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return id(response);
    }

    private long createIssue(long repositoryId, Account author, String title) throws Exception {
        String response = mvc.perform(post("/api/repositories/" + repositoryId + "/issues").header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return id(response);
    }

    private Account account(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}";
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number userId = JsonPath.read(response, "$.user.id");
        return new Account(userId.longValue(), JsonPath.read(response, "$.accessToken"));
    }

    private long id(String json) { Number value = JsonPath.read(json, "$.id"); return value.longValue(); }
    private String bearer(Account account) { return "Bearer " + account.token; }
    private record Account(Long id, String token) { }
}
