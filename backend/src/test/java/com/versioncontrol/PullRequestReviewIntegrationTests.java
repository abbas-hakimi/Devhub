package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.PullRequest;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.User;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.repository.RepositoryRepository;
import com.versioncontrol.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class PullRequestReviewIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired RepositoryRepository repositories;
    @Autowired UserRepository users;
    @Autowired BranchRepository branches;
    @Autowired PullRequestRepository pullRequests;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayV6CreatesReviewTablesAndRepositoryScopedConstraints() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '6'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name) in ('pull_request_reviews', 'pull_request_review_comments')", Integer.class)).isEqualTo(2);
    }

    @Test
    void submitsHistoricalCommentReviewWithInlineCommentAndListsItPublicly() throws Exception {
        Account owner = register("reviewcommentowner");
        Account author = register("reviewcommentauthor");
        TestPr pr = createPr(owner, author, "PUBLIC", "src/AuthService.java", "old\nnew\n", "old\n");

        String reviewJson = mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id())
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"CHANGES_REQUESTED\",\"body\":\"Please improve token validation.\","
                                + "\"comments\":[{\"path\":\"src/AuthService.java\",\"lineNumber\":2,\"side\":\"NEW\","
                                + "\"body\":\"Handle the expired token here.\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewer.id").value(owner.id()))
                .andExpect(jsonPath("$.state").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.reviewedHeadCommitHash").value(pr.featureHead()))
                .andExpect(jsonPath("$.comments.length()").value(1))
                .andExpect(jsonPath("$.comments[0].path").value("src/AuthService.java"))
                .andReturn().getResponse().getContentAsString();
        Number commentId = JsonPath.read(reviewJson, "$.comments[0].id");

        mvc.perform(get("/api/pull-requests/{id}/reviews", pr.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/pull-requests/{id}/comments", pr.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comments[0].reviewedHeadCommitHash").value(pr.featureHead()));
        mvc.perform(get("/api/pull-requests/{id}/review-status", pr.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentChangeRequests").value(1))
                .andExpect(jsonPath("$.approvalThresholdConfigured").value(false))
                .andExpect(jsonPath("$.requiredReviewConditionsSatisfied").value(nullValue()));

        mvc.perform(put("/api/pull-requests/comments/{id}", commentId.longValue()).header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Changed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/pull-requests/comments/{id}", commentId.longValue()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Updated after review\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value("Updated after review"));
        mvc.perform(delete("/api/pull-requests/comments/{id}", commentId.longValue()).header("Authorization", bearer(author)))
                .andExpect(status().isForbidden());
        String standaloneJson = mvc.perform(post("/api/pull-requests/{id}/comments", pr.id()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"src/AuthService.java\",\"lineNumber\":1,\"side\":\"OLD\",\"body\":\"Remove this comment later\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number standaloneId = JsonPath.read(standaloneJson, "$.id");
        mvc.perform(delete("/api/pull-requests/comments/{id}", standaloneId.longValue()).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsSelfApprovalAndReviewsWithoutRequiredContentAndBlocksClosedPrReviews() throws Exception {
        Account owner = register("reviewselfowner");
        TestPr pr = createPr(owner, owner, "PUBLIC", "file.txt", "new\n", "old\n");
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
        Account outsider = register("reviewselfoutsider");
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"COMMENTED\",\"body\":\"No write permission\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"CHANGES_REQUESTED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"NOT_A_STATE\",\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/pull-requests/{id}/close", pr.id()).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"COMMENTED\",\"body\":\"Note\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/pull-requests/{id}/reviews", pr.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void validatesInlinePathsSidesLinesAndBinaryFilesAgainstCurrentDiff() throws Exception {
        Account owner = register("reviewpathowner");
        Account author = register("reviewpathauthor");
        TestPr pr = createPr(owner, author, "PUBLIC", "changed.txt", "new\n", "old\n");
        submitComment(owner, pr.id(), "outside.txt", 1, "NEW", "not in diff").andExpect(status().isBadRequest());
        mvc.perform(get("/api/pull-requests/{id}/reviews", pr.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        submitComment(owner, pr.id(), "changed.txt", 1, "NEW", "valid comment").andExpect(status().isCreated());
        submitComment(owner, pr.id(), "changed.txt", 2, "NEW", "past eof").andExpect(status().isBadRequest());
        submitComment(owner, pr.id(), "changed.txt", 1, "OLD", "old side line").andExpect(status().isCreated());
        submitComment(owner, pr.id(), "changed.txt", 2, "OLD", "past old eof").andExpect(status().isBadRequest());

        TestPr binaryPr = createPr(owner, author, "PUBLIC", "asset.bin", "", "");
        // Replace the feature snapshot with an actual binary object by using a Base64 commit.
        String head = commit(owner, binaryPr.repositoryId(), "feature/review", binaryPr.featureHead(),
                "[{\"path\":\"asset.bin\",\"contentBase64\":\"AAEC/w==\"}]");
        assertThat(head).isNotBlank();
        submitComment(owner, binaryPr.id(), "asset.bin", 1, "NEW", "binary line").andExpect(status().isBadRequest());
        submitComment(owner, binaryPr.id(), "../secret", 1, "NEW", "traversal").andExpect(status().isBadRequest());
    }

    @Test
    void approvalsBecomeStaleWhenSourceHeadMovesAndCommentedReviewsDoNotEraseDecisions() throws Exception {
        Account owner = register("reviewstatusowner");
        Account author = register("reviewstatusauthor");
        TestPr pr = createPr(owner, author, "PUBLIC", "status.txt", "feature\n", "base\n");
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"APPROVED\",\"body\":\"Looks good\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/pull-requests/{id}/review-status", pr.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentApprovals").value(1)).andExpect(jsonPath("$.staleApprovals").value(0));

        String newer = commit(owner, pr.repositoryId(), "feature/review", pr.featureHead(),
                "[{\"path\":\"later.txt\",\"content\":\"later\"}]");
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"COMMENTED\",\"body\":\"One thought\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/pull-requests/{id}/review-status", pr.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReviews").value(2)).andExpect(jsonPath("$.currentApprovals").value(0))
                .andExpect(jsonPath("$.staleApprovals").value(1)).andExpect(jsonPath("$.currentSourceHeadHash").value(newer))
                .andExpect(jsonPath("$.latestEffectiveReviews[0].state").value("APPROVED"))
                .andExpect(jsonPath("$.latestEffectiveReviews[0].stale").value(true));
    }

    @Test
    void privateReviewDataIsHiddenAndAnonymousCannotSubmit() throws Exception {
        Account owner = register("reviewprivateowner");
        Account outsider = register("reviewprivateuser");
        TestPr pr = createPr(owner, outsider, "PRIVATE", "private.txt", "new\n", "old\n");
        mvc.perform(get("/api/pull-requests/{id}/reviews", pr.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/pull-requests/{id}/comments", pr.id()).header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"COMMENTED\",\"body\":\"anonymous\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"CHANGES_REQUESTED\",\"body\":\"No access\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewAndBranchCommitRaceCapturesOneConsistentSourceHead() throws Exception {
        Account owner = register("reviewraceowner");
        Account author = register("reviewraceauthor");
        TestPr pr = createPr(owner, author, "PUBLIC", "race.txt", "old\n", "base\n");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var commitFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/repositories/{id}/commits", pr.repositoryId()).header("Authorization", bearer(owner))
                                .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"feature/review\",\"expectedHeadHash\":\""
                                        + pr.featureHead() + "\",\"message\":\"race\",\"changes\":[{\"path\":\"later.txt\",\"content\":\"later\"}] }"))
                        .andReturn().getResponse();
            });
            var reviewFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/pull-requests/{id}/reviews", pr.id()).header("Authorization", bearer(owner))
                                .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"APPROVED\",\"body\":\"Race check\"}"))
                        .andReturn().getResponse();
            });
            start.countDown();
            var commitResponse = commitFuture.get();
            var reviewResponse = reviewFuture.get();
            assertThat(commitResponse.getStatus()).isEqualTo(201);
            assertThat(reviewResponse.getStatus()).isEqualTo(201);
            String reviewedHash = JsonPath.read(reviewResponse.getContentAsString(), "$.reviewedHeadCommitHash");
            String committedHash = JsonPath.read(commitResponse.getContentAsString(), "$.commitHash");
            assertThat(reviewedHash).isIn(pr.featureHead(), committedHash);
        }
    }

    private org.springframework.test.web.servlet.ResultActions submitComment(Account user, long prId,
            String path, int line, String side, String body) throws Exception {
        return mvc.perform(post("/api/pull-requests/{id}/reviews", prId).header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"COMMENTED\",\"comments\":[{\"path\":\""
                        + path + "\",\"lineNumber\":" + line + ",\"side\":\"" + side + "\",\"body\":\"" + body + "\"}]}"));
    }

    private TestPr createPr(Account owner, Account author, String visibility, String path, String featureContent,
            String baseContent) throws Exception {
        String repoName = "review-" + System.nanoTime();
        String repoJson = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + repoName + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long repoId = ((Number) JsonPath.read(repoJson, "$.id")).longValue();
        String initJson = mvc.perform(post("/api/repositories/{id}/git/init", repoId).header("Authorization", bearer(owner)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String initial = JsonPath.read(initJson, "$.commitHash");
        mvc.perform(post("/api/repositories/{id}/branches", repoId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"feature/review\",\"fromCommitHash\":\"" + initial + "\"}"))
                .andExpect(status().isCreated());
        String feature = commit(owner, repoId, "feature/review", initial,
                "[{\"path\":\"" + path + "\",\"content\":\"" + featureContent.replace("\n", "\\n") + "\"}]");
        String base = commit(owner, repoId, "main", initial,
                "[{\"path\":\"" + path + "\",\"content\":\"" + baseContent.replace("\n", "\\n") + "\"}]");
        long prId = createPullRequest(owner, author, repoId, "feature/review");
        return new TestPr(repoId, prId, feature, base);
    }

    private long createPullRequest(Account owner, long repoId, String source, String target) throws Exception {
        String result = mvc.perform(post("/api/repositories/{id}/pull-requests", repoId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Review request\",\"sourceBranch\":\""
                                + source + "\",\"targetBranch\":\"" + target + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(result, "$.id")).longValue();
    }

    private long createPullRequest(Account owner, Account author, long repoId, String source) throws Exception {
        if (author.id() == owner.id()) return createPullRequest(owner, repoId, source, "main");
        Repository repository = repositories.findById(repoId).orElseThrow();
        User authorEntity = users.findById(author.id()).orElseThrow();
        Branch sourceBranch = branches.findByRepository_IdAndName(repoId, source).orElseThrow();
        Branch targetBranch = branches.findByRepository_IdAndName(repoId, "main").orElseThrow();
        PullRequest pr = new PullRequest(repository, 1, "Review request", null, authorEntity, sourceBranch, targetBranch,
                sourceBranch.getHeadCommit(), targetBranch.getHeadCommit());
        return pullRequests.saveAndFlush(pr).getId();
    }

    private String commit(Account owner, long repositoryId, String branch, String expectedHead, String changes) throws Exception {
        String json = mvc.perform(post("/api/repositories/{id}/commits", repositoryId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"" + branch + "\",\"expectedHeadHash\":\""
                                + expectedHead + "\",\"message\":\"review setup\",\"changes\":" + changes + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.commitHash");
    }

    private Account register(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(json, "$.user.id")).longValue(), JsonPath.read(json, "$.accessToken"));
    }

    private String bearer(Account user) { return "Bearer " + user.token(); }
    private record Account(long id, String token) { }
    private record TestPr(long repositoryId, long id, String featureHead, String baseHead) { }
}
