package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.entity.NotificationEventType;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryActivityEventType;
import com.versioncontrol.repository.RepositoryActivityRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.UserNotificationRepository;
import com.versioncontrol.service.NotificationService;
import com.versioncontrol.service.RepositoryActivityService;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationActivityIntegrationTests {
    private static final AtomicLong IDS = new AtomicLong();
    @Autowired MockMvc mvc;
    @Autowired UserNotificationRepository notificationRows;
    @Autowired RepositoryActivityRepository activityRows;
    @Autowired RepositoryRepository repositories;
    @Autowired NotificationService notifications;
    @Autowired RepositoryActivityService activity;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void invitationNotificationHasPrivateInviteScopeAndReadLifecycleIsRecipientOnly() throws Exception {
        Account owner = register("notifyowner");
        Account invitee = register("notifyinvitee");
        long repositoryId = createRepository(owner, "PRIVATE");
        mvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        String invitation = mvc.perform(post("/api/repositories/" + repositoryId + "/collaborators/invitations")
                .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + invitee.username() + "\",\"role\":\"READ\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String notificationJson = mvc.perform(get("/api/notifications").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("COLLABORATOR_INVITED"))
                .andReturn().getResponse().getContentAsString();
        long notificationId = ((Number) JsonPath.read(notificationJson, "$.items[0].id")).longValue();
        mvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(patch("/api/notifications/" + notificationId + "/read").header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/notifications/" + notificationId + "/read").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").exists());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk()).andExpect(content -> assertThat(content.getResponse().getContentAsString()).isEqualTo("0"));
        mvc.perform(patch("/api/notifications/" + notificationId + "/unread").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").doesNotExist());
        mvc.perform(post("/api/notifications/mark-all-read").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.markedRead").value(1));
        mvc.perform(post("/api/collaboration-invitations/" + JsonPath.read(invitation, "$.invitationId") + "/accept")
                .header("Authorization", bearer(invitee))).andExpect(status().isOk());
        notifications.notifyRecipients(List.of(invitee.id()), owner.id(), repositories.findById(repositoryId).orElseThrow(),
                NotificationEventType.ISSUE_COMMENTED, "Private event", "Private repository event", "ISSUE", 1L, "private-access-check");
        String scopedNotificationJson = mvc.perform(get("/api/notifications").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        long scopedNotificationId = ((Number) JsonPath.read(scopedNotificationJson, "$.items[0].id")).longValue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/repositories/" + repositoryId + "/collaborators/" + invitee.id()).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").header("Authorization", bearer(invitee)).param("unread", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(patch("/api/notifications/" + scopedNotificationId + "/read").header("Authorization", bearer(invitee)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + repositoryId + "/activity")).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + repositoryId + "/activity").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eventType").value("REPOSITORY_CREATED"));
        assertThat(invitation).contains("invitationId");
    }

    @Test
    void discussionEventsAreVisibleInNewestFirstActivityAndNotificationsDoNotSelfNotify() throws Exception {
        Account owner = register("activityowner");
        Account writer = register("activitywriter");
        long repositoryId = createRepository(owner, "PUBLIC");
        String invitation = mvc.perform(post("/api/repositories/" + repositoryId + "/collaborators/invitations")
                .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + writer.username() + "\",\"role\":\"WRITE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long invitationId = ((Number) JsonPath.read(invitation, "$.invitationId")).longValue();
        mvc.perform(post("/api/collaboration-invitations/" + invitationId + "/accept").header("Authorization", bearer(writer)))
                .andExpect(status().isOk());
        String discussion = mvc.perform(post("/api/repositories/" + repositoryId + "/discussions")
                .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"Q_AND_A\",\"title\":\"Help topic\",\"body\":\"Details\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long discussionId = ((Number) JsonPath.read(discussion, "$.id")).longValue();
        String reply = mvc.perform(post("/api/discussions/" + discussionId + "/replies").header("Authorization", bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Suggested solution\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long replyId = ((Number) JsonPath.read(reply, "$.id")).longValue();
        mvc.perform(putAnswer(discussionId, replyId, owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").header("Authorization", bearer(owner)).param("unread", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value("DISCUSSION_REPLIED"));
        mvc.perform(get("/api/notifications").header("Authorization", bearer(writer)).param("unread", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value("DISCUSSION_ANSWER_ACCEPTED"));
        mvc.perform(get("/api/repositories/" + repositoryId + "/activity").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("DISCUSSION_ANSWER_ACCEPTED"));
        mvc.perform(get("/api/repositories/" + repositoryId + "/activity").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eventType").value("DISCUSSION_CREATED"));
        mvc.perform(get("/api/notifications").header("Authorization", bearer(writer)).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/" + repositoryId + "/activity").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notificationAndActivityWritesRollBackWithTheirBusinessTransactionAndDedupeRetries() throws Exception {
        Account owner = register("rollbackowner");
        Account other = register("rollbackother");
        long repositoryId = createRepository(owner, "PUBLIC");
        long notificationCount = notificationRows.countByRecipient_Id(other.id());
        long activityCount = activityRows.countByRepository_Id(repositoryId);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            Repository repository = repositories.findById(repositoryId).orElseThrow();
            notifications.notifyRecipients(List.of(other.id()), owner.id(), repository, NotificationEventType.ISSUE_COMMENTED,
                    "Temporary", "Rollback check", "ISSUE", 123L, "rollback-test-notification");
            activity.record(repository, owner.id(), RepositoryActivityEventType.ISSUE_OPENED, "ISSUE", 123L,
                    "Rollback check", "rollback-test-activity");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(notificationRows.countByRecipient_Id(other.id())).isEqualTo(notificationCount);
        assertThat(activityRows.countByRepository_Id(repositoryId)).isEqualTo(activityCount);
        notifications.notifyRecipients(List.of(other.id()), owner.id(), repositories.findById(repositoryId).orElseThrow(),
                NotificationEventType.ISSUE_COMMENTED, "Dedupe", "Retry check", "ISSUE", 124L, "dedupe-test-notification");
        notifications.notifyRecipients(List.of(other.id()), owner.id(), repositories.findById(repositoryId).orElseThrow(),
                NotificationEventType.ISSUE_COMMENTED, "Dedupe", "Retry check", "ISSUE", 124L, "dedupe-test-notification");
        assertThat(notificationRows.countByRecipient_Id(other.id())).isEqualTo(notificationCount + 1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putAnswer(long discussionId, long replyId, Account account) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/discussions/" + discussionId + "/accepted-answer")
                .header("Authorization", bearer(account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"replyId\":" + replyId + "}");
    }
    private long createRepository(Account owner, String visibility) throws Exception {
        String name = "phase9c-" + IDS.incrementAndGet() + "-" + System.nanoTime();
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
    private Account register(String prefix) throws Exception {
        String username = prefix + IDS.incrementAndGet() + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(username, JsonPath.read(response, "$.accessToken"), ((Number) JsonPath.read(response, "$.user.id")).longValue());
    }
    private String bearer(Account account) { return "Bearer " + account.token(); }
    private record Account(String username, String token, long id) { }
}
