package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.repository.CommitRepository;
import com.versioncontrol.repository.PullRequestRepository;
import com.versioncontrol.repository.TreeEntryRepository;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;

@SpringBootTest
@AutoConfigureMockMvc
class PullRequestMergeIntegrationTests {
    private static final AtomicLong NAMES = new AtomicLong();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BranchRepository branches;
    @Autowired CommitRepository commits;
    @Autowired PullRequestRepository pullRequests;
    @Autowired TreeEntryRepository treeEntries;
    @Autowired ObjectStorage storage;

    @Test
    void v8AndFastForwardMergeEnforceAuthorizationApprovalAndIdempotency() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where lower(table_name) = 'commits' and lower(column_name) = 'second_parent_commit_id'", Integer.class)).isEqualTo(1);
        Account owner = register("mergeffowner");
        Account reviewer = register("mergeffreviewer");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        inviteWrite(owner, reviewer, repo);
        createBranch(owner, repo, "feature/ff", initial);
        String source = commit(owner, repo, "feature/ff", initial,
                "{\"path\":\"fast.txt\",\"content\":\"fast-forward\"}");
        long pr = createPr(owner, repo, "feature/ff");
        approve(reviewer, pr);
        String url = "/api/pull-requests/" + pr;
        mvc.perform(get(url + "/mergeability")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeable").value(true))
                .andExpect(jsonPath("$.sourceHead").value(source))
                .andExpect(jsonPath("$.targetHead").value(initial))
                .andExpect(jsonPath("$.mergeBase").value(initial))
                .andExpect(jsonPath("$.fastForwardEligible").value(true))
                .andExpect(jsonPath("$.aheadBy").value(1)).andExpect(jsonPath("$.behindBy").value(0));
        String body = mergeBody("FAST_FORWARD", source, initial, "Fast-forward");
        mvc.perform(post(url + "/merge").header("Authorization", bearer(reviewer)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(url + "/merge").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url + "/merge").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("FAST_FORWARD", "0".repeat(64), initial, "stale")))
                .andExpect(status().isConflict());
        mvc.perform(post(url + "/merge").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("MERGED"))
                .andExpect(jsonPath("$.mergeCommitHash").value(nullValue()));
        assertThat(head(repo, "main")).isEqualTo(source);
        assertThat(pullRequests.findDetailedById(pr).orElseThrow().getMergedBy().getId()).isEqualTo(owner.id());
        mvc.perform(post(url + "/merge").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void divergentMergeCreatesTwoParentCommitAndMergeBaseUsesBothParents() throws Exception {
        Account owner = register("mergetwoparent");
        Account reviewer = register("mergetworeviewer");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        inviteWrite(owner, reviewer, repo);
        createBranch(owner, repo, "feature/diverged", initial);
        String source = commit(owner, repo, "feature/diverged", initial,
                "{\"path\":\"feature.txt\",\"content\":\"feature\"}");
        String target = commit(owner, repo, "main", initial,
                "{\"path\":\"target.txt\",\"content\":\"target\"}");
        long pr = createPr(owner, repo, "feature/diverged");
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"APPROVED\",\"body\":\"self approval\"}"))
                .andExpect(status().isForbidden());
        approve(reviewer, pr);
        mvc.perform(get("/api/pull-requests/{id}/mergeability", pr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeable").value(true)).andExpect(jsonPath("$.fastForwardEligible").value(false))
                .andExpect(jsonPath("$.aheadBy").value(1)).andExpect(jsonPath("$.behindBy").value(1))
                .andExpect(jsonPath("$.conflictPaths.length()").value(0));
        mvc.perform(post("/api/pull-requests/{id}/merge", pr).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody("FAST_FORWARD", source, target, "Must not fast-forward")))
                .andExpect(status().isConflict());
        String merged = mvc.perform(post("/api/pull-requests/{id}/merge", pr).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody("MERGE_COMMIT", source, target, "Merge independent files")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("MERGED"))
                .andReturn().getResponse().getContentAsString();
        String mergeHash = JsonPath.read(merged, "$.mergeCommitHash");
        Commit merge = commits.findDetailedByCommitHash(mergeHash).orElseThrow();
        var parents = jdbc.queryForMap("select first_parent.commit_hash as first_hash, second_parent.commit_hash as second_hash "
                + "from commits merge_commit join commits first_parent on first_parent.id = merge_commit.parent_commit_id "
                + "join commits second_parent on second_parent.id = merge_commit.second_parent_commit_id "
                + "where merge_commit.commit_hash = ?", mergeHash);
        assertThat(parents.get("FIRST_HASH")).isEqualTo(target);
        assertThat(parents.get("SECOND_HASH")).isEqualTo(source);
        assertThat(merge.getParentCommit().getId()).isNotEqualTo(merge.getSecondParentCommit().getId());
        assertThat(commitContent(merge, "feature.txt")).isEqualTo("feature");
        assertThat(commitContent(merge, "target.txt")).isEqualTo("target");
        assertThat(head(repo, "main")).isEqualTo(mergeHash);

        createBranch(owner, repo, "feature/after-merge", mergeHash);
        commit(owner, repo, "feature/after-merge", mergeHash, "{\"path\":\"later.txt\",\"content\":\"later\"}");
        mvc.perform(get("/api/repositories/{id}/compare", repo).param("base", "main").param("head", "feature/after-merge"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mergeBaseHash").value(mergeHash))
                .andExpect(jsonPath("$.aheadBy").value(1)).andExpect(jsonPath("$.behindBy").value(0));
    }

    @Test
    void mergesDisjointTextEditsAndIdenticalChanges() throws Exception {
        Account owner = register("mergetextowner");
        Account reviewer = register("mergetextreviewer");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        String base = commit(owner, repo, "main", initial, "{\"path\":\"shared.txt\",\"content\":\"one\\ntwo\\nthree\\n\"}");
        inviteWrite(owner, reviewer, repo);
        createBranch(owner, repo, "feature/text", base);
        String source = commit(owner, repo, "feature/text", base,
                "{\"path\":\"shared.txt\",\"content\":\"ONE\\ntwo\\nthree\\n\"},"
                + "{\"path\":\"same.txt\",\"content\":\"identical\"}");
        String target = commit(owner, repo, "main", base,
                "{\"path\":\"shared.txt\",\"content\":\"one\\ntwo\\nTHREE\\n\"},"
                + "{\"path\":\"same.txt\",\"content\":\"identical\"}");
        long pr = createPr(owner, repo, "feature/text");
        approve(reviewer, pr);
        mvc.perform(get("/api/pull-requests/{id}/mergeability", pr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeable").value(true)).andExpect(jsonPath("$.conflictPaths.length()").value(0));
        String response = mvc.perform(post("/api/pull-requests/{id}/merge", pr).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody("MERGE_COMMIT", source, target, "Merge text")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Commit merge = commits.findDetailedByCommitHash(JsonPath.read(response, "$.mergeCommitHash")).orElseThrow();
        assertThat(commitContent(merge, "shared.txt")).isEqualTo("ONE\ntwo\nTHREE\n");
        assertThat(commitContent(merge, "same.txt")).isEqualTo("identical");
    }

    @Test
    void reportsTextBinaryAndFileDirectoryConflictsWithoutMutatingState() throws Exception {
        Account owner = register("mergeconflictowner");
        Account reviewer = register("mergeconflictreviewer");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        String base = commit(owner, repo, "main", initial,
                "{\"path\":\"text.txt\",\"content\":\"one\\ntwo\\nthree\\n\"},"
                + "{\"path\":\"binary.dat\",\"contentBase64\":\"AAEC\"}");
        inviteWrite(owner, reviewer, repo);
        createBranch(owner, repo, "feature/conflicts", base);
        String source = commit(owner, repo, "feature/conflicts", base,
                "{\"path\":\"text.txt\",\"content\":\"one\\nsource\\nthree\\n\"},"
                + "{\"path\":\"binary.dat\",\"contentBase64\":\"AAED\"},"
                + "{\"path\":\"node\",\"content\":\"file\"}");
        String target = commit(owner, repo, "main", base,
                "{\"path\":\"text.txt\",\"content\":\"one\\ntarget\\nthree\\n\"},"
                + "{\"path\":\"binary.dat\",\"contentBase64\":\"AAEE\"},"
                + "{\"path\":\"node/child.txt\",\"content\":\"directory\"}");
        long pr = createPr(owner, repo, "feature/conflicts");
        approve(reviewer, pr);
        long commitCount = commits.countByRepository_Id(repo);
        String mergeability = mvc.perform(get("/api/pull-requests/{id}/mergeability", pr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeable").value(false)).andReturn().getResponse().getContentAsString();
        List<String> paths = JsonPath.read(mergeability, "$.conflictPaths");
        assertThat(paths).containsExactly("binary.dat", "node", "node/child.txt", "text.txt");
        mvc.perform(post("/api/pull-requests/{id}/merge", pr).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody("MERGE_COMMIT", source, target, "Conflicting merge")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.conflictPaths.length()").value(4));
        assertThat(head(repo, "main")).isEqualTo(target);
        assertThat(commits.countByRepository_Id(repo)).isEqualTo(commitCount);
        assertThat(pullRequests.findDetailedById(pr).orElseThrow().getState()).isEqualTo(com.versioncontrol.entity.PullRequestState.OPEN);
    }

    @Test
    void blocksStaleApprovalsAndChangeRequestsAndSerializesConcurrentMerges() throws Exception {
        Account owner = register("mergeracesowner");
        Account reviewer = register("mergeracesreviewer");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        inviteWrite(owner, reviewer, repo);
        createBranch(owner, repo, "feature/stale", initial);
        String source1 = commit(owner, repo, "feature/stale", initial, "{\"path\":\"one.txt\",\"content\":\"one\"}");
        long pr = createPr(owner, repo, "feature/stale");
        approve(reviewer, pr);
        String source2 = commit(owner, repo, "feature/stale", source1, "{\"path\":\"two.txt\",\"content\":\"two\"}");
        mvc.perform(get("/api/pull-requests/{id}/mergeability", pr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeable").value(false)).andExpect(jsonPath("$.currentApprovals").value(0))
                .andExpect(jsonPath("$.reason").value("A current non-author approval is required"));
        mvc.perform(post("/api/pull-requests/{id}/merge", pr).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("FAST_FORWARD", source2, initial, "stale review")))
                .andExpect(status().isConflict());
        approve(reviewer, pr);
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr).header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"CHANGES_REQUESTED\",\"body\":\"please revise\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/pull-requests/{id}/mergeability", pr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeable").value(false)).andExpect(jsonPath("$.currentChangeRequests").value(1));
        mvc.perform(post("/api/pull-requests/{id}/merge", pr).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("FAST_FORWARD", source2, initial, "changes requested")))
                .andExpect(status().isConflict());
        approve(reviewer, pr);
        String body = mergeBody("FAST_FORWARD", source2, initial, "concurrent");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> mergeAtBarrier(start, pr, owner, body));
            var second = executor.submit(() -> mergeAtBarrier(start, pr, owner, body));
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(head(repo, "main")).isEqualTo(source2);
        assertThat(pullRequests.findDetailedById(pr).orElseThrow().getState()).isEqualTo(com.versioncontrol.entity.PullRequestState.MERGED);
    }

    @Test
    void serializesMergeAgainstConcurrentSourceHeadUpdate() throws Exception {
        Account owner = register("mergeheadraceowner");
        Account reviewer = register("mergeheadracereviewer");
        Account writer = register("mergeheadracewriter");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        inviteWrite(owner, writer, repo);
        inviteWrite(owner, reviewer, repo);
        createBranch(owner, repo, "feature/head-race", initial);
        String source = commit(writer, repo, "feature/head-race", initial,
                "{\"path\":\"source.txt\",\"content\":\"source\"}");
        long pr = createPr(owner, repo, "feature/head-race");
        approve(reviewer, pr);
        String mergeBody = mergeBody("FAST_FORWARD", source, initial, "Merge against racing source update");
        CountDownLatch start = new CountDownLatch(1);
        int mergeStatus;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
                return mvc.perform(post("/api/repositories/{id}/commits", repo)
                                .header("Authorization", bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"branch\":\"feature/head-race\",\"expectedHeadHash\":\"" + source
                                        + "\",\"message\":\"concurrent source update\",\"changes\":[{\"path\":\"later.txt\",\"content\":\"later\"}]}"))
                        .andReturn().getResponse().getStatus();
            });
            var merge = executor.submit(() -> mergeAtBarrier(start, pr, owner, mergeBody));
            start.countDown();
            assertThat(update.get(30, TimeUnit.SECONDS)).isEqualTo(201);
            mergeStatus = merge.get(30, TimeUnit.SECONDS);
        }
        String currentSource = head(repo, "feature/head-race");
        var finalState = pullRequests.findDetailedById(pr).orElseThrow().getState();
        if (mergeStatus == 200) {
            // Merge acquired the repository lock first. It fast-forwarded to the requested source,
            // then the concurrent commit advanced the feature branch from that source.
            assertThat(head(repo, "main")).isEqualTo(source);
            assertThat(currentSource).isNotEqualTo(source);
            assertThat(finalState).isEqualTo(com.versioncontrol.entity.PullRequestState.MERGED);
        } else {
            assertThat(mergeStatus).isEqualTo(409);
            // The source commit acquired the lock first, so the merge observed stale expected HEADs.
            assertThat(currentSource).isNotEqualTo(source);
            assertThat(head(repo, "main")).isEqualTo(initial);
            assertThat(finalState).isEqualTo(com.versioncontrol.entity.PullRequestState.OPEN);
        }
    }

    @Test
    void closedPullRequestCannotBeMerged() throws Exception {
        Account owner = register("mergeclosedowner");
        Account reviewer = register("mergeclosedreviewer");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        inviteWrite(owner, reviewer, repo);
        createBranch(owner, repo, "feature/closed", initial);
        String source = commit(owner, repo, "feature/closed", initial,
                "{\"path\":\"closed.txt\",\"content\":\"change\"}");
        long pr = createPr(owner, repo, "feature/closed");
        approve(reviewer, pr);
        mvc.perform(post("/api/pull-requests/{id}/close", pr).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CLOSED"));
        mvc.perform(post("/api/pull-requests/{id}/merge", pr).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody("FAST_FORWARD", source, initial, "closed")))
                .andExpect(status().isConflict());
        assertThat(head(repo, "main")).isEqualTo(initial);
        assertThat(pullRequests.findDetailedById(pr).orElseThrow().getState()).isEqualTo(com.versioncontrol.entity.PullRequestState.CLOSED);
    }

    @Test
    void reportsAddAddAndModifyDeleteConflicts() throws Exception {
        Account owner = register("mergeadddeleteowner");
        Account reviewer = register("mergeadddeletereviewer");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        String base = commit(owner, repo, "main", initial,
                "{\"path\":\"modify-delete.txt\",\"content\":\"base\"}");
        inviteWrite(owner, reviewer, repo);
        createBranch(owner, repo, "feature/add-delete", base);
        mvc.perform(post("/api/repositories/{id}/commits", repo).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"feature/add-delete\",\"expectedHeadHash\":\""
                                + base + "\",\"message\":\"source changes\",\"changes\":[{\"path\":\"added.txt\",\"content\":\"source\"}],\"deletedPaths\":[\"modify-delete.txt\"]}"))
                .andExpect(status().isCreated());
        commit(owner, repo, "main", base,
                "{\"path\":\"added.txt\",\"content\":\"target\"},"
                        + "{\"path\":\"modify-delete.txt\",\"content\":\"modified\"}");
        long pr = createPr(owner, repo, "feature/add-delete");
        approve(reviewer, pr);
        mvc.perform(get("/api/pull-requests/{id}/mergeability", pr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeable").value(false))
                .andExpect(jsonPath("$.conflictPaths").value(org.hamcrest.Matchers.containsInAnyOrder("added.txt", "modify-delete.txt")));
    }

    @Test
    void detectsAmbiguousMergeBaseInCrissCrossHistory() throws Exception {
        Account owner = register("mergecrisscrossowner");
        Account reviewer = register("mergecrisscrossreviewer");
        long repo = createRepository(owner, "PUBLIC");
        String initial = initialize(owner, repo);
        inviteWrite(owner, reviewer, repo);
        String left = commit(owner, repo, "main", initial, "{\"path\":\"left.txt\",\"content\":\"left\"}");
        createBranch(owner, repo, "feature/right", initial);
        String right = commit(owner, repo, "feature/right", initial, "{\"path\":\"right.txt\",\"content\":\"right\"}");

        long leftMergePr = createPr(owner, repo, "feature/right");
        approve(reviewer, leftMergePr);
        mvc.perform(post("/api/pull-requests/{id}/merge", leftMergePr).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody("MERGE_COMMIT", right, left, "left merge")))
                .andExpect(status().isOk());
        createBranch(owner, repo, "feature/left-original", left);
        long rightMergePr = createPrForBranches(owner, repo, "feature/left-original", "feature/right");
        approve(reviewer, rightMergePr);
        mvc.perform(post("/api/pull-requests/{id}/merge", rightMergePr).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody("MERGE_COMMIT", left, right, "right merge")))
                .andExpect(status().isOk());

        long crissCrossPr = createPrForBranches(owner, repo, "feature/right", "main");
        mvc.perform(get("/api/pull-requests/{id}/mergeability", crissCrossPr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeable").value(false))
                .andExpect(jsonPath("$.reason").value("History has multiple best merge bases and requires manual resolution"));
    }

    private int mergeAtBarrier(CountDownLatch start, long pr, Account owner, String body) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("merge requests did not reach barrier");
        return mvc.perform(post("/api/pull-requests/{id}/merge", pr).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }
    private String commitContent(Commit commit, String name) throws Exception {
        var row = treeEntries.findByTree_IdAndName(commit.getRootTree().getId(), name).orElseThrow();
        try (InputStream input = storage.read(row.getBlob().getObjectHash())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private String mergeBody(String strategy, String source, String target, String message) {
        return "{\"strategy\":\"" + strategy + "\",\"expectedSourceHead\":\"" + source
                + "\",\"expectedTargetHead\":\"" + target + "\",\"commitMessage\":\"" + message + "\"}";
    }
    private void inviteWrite(Account owner, Account invitee, long repo) throws Exception {
        String result = mvc.perform(post("/api/repositories/{id}/collaborators/invitations", repo)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + invitee.username() + "\",\"role\":\"WRITE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long invitation = ((Number) JsonPath.read(result, "$.invitationId")).longValue();
        mvc.perform(post("/api/collaboration-invitations/{id}/accept", invitation)
                        .header("Authorization", bearer(invitee))).andExpect(status().isOk());
    }
    private void approve(Account reviewer, long pr) throws Exception {
        mvc.perform(post("/api/pull-requests/{id}/reviews", pr).header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"APPROVED\",\"body\":\"approved\"}"))
                .andExpect(status().isCreated());
    }
    private long createPr(Account author, long repo, String sourceBranch) throws Exception {
        return createPrForBranches(author, repo, sourceBranch, "main");
    }
    private long createPrForBranches(Account author, long repo, String sourceBranch, String targetBranch) throws Exception {
        String result = mvc.perform(post("/api/repositories/{id}/pull-requests", repo).header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Merge test\",\"sourceBranch\":\""
                                + sourceBranch + "\",\"targetBranch\":\"" + targetBranch + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(result, "$.id")).longValue();
    }
    private String commit(Account author, long repo, String branch, String expected, String changes) throws Exception {
        String result = mvc.perform(post("/api/repositories/{id}/commits", repo).header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"" + branch + "\",\"expectedHeadHash\":\""
                                + expected + "\",\"message\":\"test commit\",\"changes\":[" + changes + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(result, "$.commitHash");
    }
    private long createRepository(Account owner, String visibility) throws Exception {
        String name = "merge-repo-" + NAMES.incrementAndGet() + "-" + System.nanoTime();
        String result = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(result, "$.id")).longValue();
    }
    private String initialize(Account owner, long repo) throws Exception {
        String result = mvc.perform(post("/api/repositories/{id}/git/init", repo).header("Authorization", bearer(owner)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(result, "$.commitHash");
    }
    private void createBranch(Account owner, long repo, String name, String from) throws Exception {
        mvc.perform(post("/api/repositories/{id}/branches", repo).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"fromCommitHash\":\"" + from + "\"}"))
                .andExpect(status().isCreated());
    }
    private String head(long repo, String name) throws Exception {
        String branchesJson = mvc.perform(get("/api/repositories/{id}/branches", repo)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<java.util.Map<String, Object>> list = JsonPath.read(branchesJson, "$");
        return list.stream().filter(row -> name.equals(row.get("name"))).findFirst()
                .map(row -> (String) ((java.util.Map<?, ?>) row.get("headCommit")).get("commitHash")).orElseThrow();
    }
    private Account register(String prefix) throws Exception {
        String username = prefix + NAMES.incrementAndGet() + System.nanoTime();
        String result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(result, "$.user.id")).longValue(), username, JsonPath.read(result, "$.accessToken"));
    }
    private String bearer(Account account) { return "Bearer " + account.token(); }
    private record Account(Long id, String username, String token) { }
}
