package com.versioncontrol;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MilestoneIntegrationTests {
    @Autowired MockMvc mvc;

    @Test
    void milestoneCrudValidationDuplicatesVisibilityAndLifecycle() throws Exception {
        Account owner = account("milestoneowner");
        Account outsider = account("milestoneoutsider");
        long repositoryId = repository(owner, false);
        long otherRepositoryId = repository(owner, false);

        mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(outsider))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"blocked\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"bad date\",\"dueDate\":\"not-a-date\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"too long description\",\"description\":\"" + "x".repeat(16001) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"overdue\",\"dueDate\":\"2000-01-01\"}"))
                .andExpect(status().isCreated());

        String created = mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  Release 1  \",\"description\":\"  ship it  \",\"dueDate\":\"2026-11-15\",\"ownerId\":999}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Release 1"))
                .andExpect(jsonPath("$.description").value("ship it")).andExpect(jsonPath("$.dueDate").value("2026-11-15"))
                .andExpect(jsonPath("$.state").value("OPEN")).andExpect(jsonPath("$.closedAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty()).andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.repository.id").value(repositoryId)).andReturn().getResponse().getContentAsString();
        long milestoneId = id(created);

        mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Release 1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"release 1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/repositories/" + otherRepositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"release 1\"}"))
                .andExpect(status().isCreated());
        createMilestone(repositoryId, owner, "target title");

        mvc.perform(get("/api/repositories/" + repositoryId + "/milestones"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].title").value("overdue"))
                .andExpect(jsonPath("$[2].title").value("target title"))
                .andExpect(content().string(containsString("Release 1")));
        mvc.perform(get("/api/milestones/" + milestoneId)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(milestoneId));
        mvc.perform(get("/api/milestones/99999999")).andExpect(status().isNotFound());
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(outsider))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/milestones/99999999").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" \"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"TARGET TITLE\",\"state\":\"OPEN\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/milestones/" + milestoneId).header("Authorization", bearer(outsider))).andExpect(status().isForbidden());

        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Description only update\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Release 1"))
                .andExpect(jsonPath("$.description").value("Description only update"))
                .andExpect(jsonPath("$.dueDate").value("2026-11-15")).andExpect(jsonPath("$.state").value("OPEN"));
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Release 1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Release 1"))
                .andExpect(jsonPath("$.description").value("Description only update"))
                .andExpect(jsonPath("$.dueDate").value("2026-11-15"));
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueDate\":\"2026-11-20\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Release 1"))
                .andExpect(jsonPath("$.description").value("Description only update"))
                .andExpect(jsonPath("$.dueDate").value("2026-11-20"));
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"CLOSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Release 1"))
                .andExpect(jsonPath("$.state").value("CLOSED")).andExpect(jsonPath("$.closedAt").isNotEmpty());
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueDate\":null,\"description\":null,\"state\":\"OPEN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.dueDate").doesNotExist()).andExpect(jsonPath("$.closedAt").doesNotExist());

        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Release 1\",\"description\":\"Updated\",\"dueDate\":\"2026-12-01\",\"state\":\"BROKEN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Release 1\",\"description\":\"Updated\",\"dueDate\":\"2026-12-01\",\"state\":\"CLOSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Updated"))
                .andExpect(jsonPath("$.dueDate").value("2026-12-01")).andExpect(jsonPath("$.closedAt").isNotEmpty());
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Release 1\",\"description\":null,\"dueDate\":null,\"state\":\"OPEN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closedAt").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist()).andExpect(jsonPath("$.dueDate").doesNotExist());
        mvc.perform(put("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \",\"state\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void privateRepositoryMilestonesFollowRepositoryVisibilityRules() throws Exception {
        Account owner = account("private-milestone-owner");
        Account outsider = account("private-milestone-outsider");
        long repositoryId = repository(owner, true);
        String response = mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"private release\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long milestoneId = id(response);
        mvc.perform(get("/api/repositories/" + repositoryId + "/milestones")).andExpect(status().isNotFound());
        mvc.perform(get("/api/milestones/" + milestoneId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/milestones/" + milestoneId).header("Authorization", bearer(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/milestones/" + milestoneId).header("Authorization", bearer(owner))).andExpect(status().isOk());
        mvc.perform(post("/api/repositories/99999999/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"missing repo\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/milestones/99999999").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"missing\",\"state\":\"OPEN\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/milestones/99999999").header("Authorization", bearer(owner))).andExpect(status().isNotFound());
    }

    @Test
    void repositoryDeletionStillRequiresMilestoneCleanup() throws Exception {
        Account owner = account("milestone-delete-guard");
        long repositoryId = repository(owner, false);
        long milestoneId = createMilestone(repositoryId, owner, "must be removed first");

        mvc.perform(delete("/api/repositories/" + repositoryId).header("Authorization", bearer(owner)))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/milestones/" + milestoneId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/repositories/" + repositoryId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
    }

    @Test
    void issueMilestoneAssignmentPresenceAuthorizationBoundariesAndDeletion() throws Exception {
        Account owner = account("assignment-owner");
        Account author = account("assignment-author");
        long repositoryId = repository(owner, false);
        long otherRepositoryId = repository(owner, false);
        long milestoneId = createMilestone(repositoryId, owner, "release");
        long otherMilestoneId = createMilestone(otherRepositoryId, owner, "other release");
        long issueId = createIssue(repositoryId, author);

        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"edited\",\"milestoneId\":" + milestoneId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.milestone.id").value(milestoneId));
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"still assigned\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.milestone.id").value(milestoneId));
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"edited by author\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.milestone.id").value(milestoneId));
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneId\":null}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneId\":" + otherMilestoneId + "}"))
                .andExpect(status().isBadRequest());
        long replacementMilestoneId = createMilestone(repositoryId, owner, "replacement");
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneId\":" + replacementMilestoneId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.milestone.id").value(replacementMilestoneId));
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneId\":" + milestoneId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.milestone.id").value(milestoneId));
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneId\":99999999}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/issues/99999999").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneId\":null}"))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneId\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.milestone").doesNotExist());
        mvc.perform(put("/api/issues/" + issueId).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneId\":" + milestoneId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.milestone.id").value(milestoneId));
        mvc.perform(delete("/api/milestones/" + milestoneId).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/issues/" + issueId)).andExpect(status().isOk()).andExpect(jsonPath("$.milestone").doesNotExist());
    }

    private long createMilestone(long repositoryId, Account owner, String title) throws Exception {
        String response = mvc.perform(post("/api/repositories/" + repositoryId + "/milestones").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return id(response);
    }
    private long createIssue(long repositoryId, Account author) throws Exception {
        String response = mvc.perform(post("/api/repositories/" + repositoryId + "/issues").header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"issue\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return id(response);
    }
    private long repository(Account owner, boolean privateRepo) throws Exception {
        String body = "{\"name\":\"repo-" + System.nanoTime() + "\",\"visibility\":\"" + (privateRepo ? "PRIVATE" : "PUBLIC") + "\"}";
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
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
    private long id(String json) { return ((Number) JsonPath.read(json, "$.id")).longValue(); }
    private String bearer(Account account) { return "Bearer " + account.token; }
    private record Account(Long id, String token) { }
}
