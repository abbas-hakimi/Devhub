package com.versioncontrol;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.repository.BranchRepository;

@SpringBootTest
@AutoConfigureMockMvc
class BranchManagementIntegrationTests {
    private static final AtomicLong NAMES = new AtomicLong();

    @Autowired MockMvc mvc;
    @Autowired BranchRepository branches;

    @Test
    void createsBranchesFromMainOrSpecifiedCommitAndRejectsDuplicatesAndInvalidNames() throws Exception {
        TestAccount owner = register("branchcreate");
        long repositoryId = createRepository(owner, "PUBLIC");
        String mainHash = initialize(owner, repositoryId);
        String path = "/api/repositories/" + repositoryId + "/branches";

        String featureJson = mvc.perform(post(path).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"feature/auth\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("feature/auth"))
                .andExpect(jsonPath("$.headCommit.commitHash").value(mainHash))
                .andExpect(jsonPath("$.headCommit.rootTreeHash").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        Number featureId = JsonPath.read(featureJson, "$.id");

        mvc.perform(post(path).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"release\",\"fromCommitHash\":\"" + mainHash + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.headCommit.commitHash").value(mainHash));
        mvc.perform(post(path).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Feature/auth\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post(path).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"feature/auth\"}"))
                .andExpect(status().isConflict());
        for (String invalidName : new String[] {"", "HEAD", "feature//bad", "bad name", "name.lock", "bad~name"}) {
            mvc.perform(post(path).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + invalidName + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(path).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"feature\\\\auth\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/branches/" + featureId.longValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headCommit.commitHash").value(mainHash));
        mvc.perform(get("/api/branches/9223372036854770000")).andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(branches.countByRepository_IdAndName(repositoryId, "main")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(branches.countByRepository_IdAndName(repositoryId, "feature/auth")).isEqualTo(1);
    }

    @Test
    void enforcesOwnerAndInitializationRequirementsAndScopedSourceCommits() throws Exception {
        TestAccount owner = register("branchowner");
        TestAccount other = register("branchnonowner");
        long uninitialized = createRepository(owner, "PUBLIC");
        String uninitializedPath = "/api/repositories/" + uninitialized + "/branches";
        mvc.perform(post(uninitializedPath).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"feature/no-init\"}"))
                .andExpect(status().isConflict());

        long repositoryA = createRepository(owner, "PUBLIC");
        String mainA = initialize(owner, repositoryA);
        String branchesA = "/api/repositories/" + repositoryA + "/branches";
        mvc.perform(post(branchesA).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"anon\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(branchesA).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"forbidden\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/repositories/9223372036854770000/branches")
                .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"missing\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(branchesA).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"missing-source\",\"fromCommitHash\":\"" + "f".repeat(64) + "\"}"))
                .andExpect(status().isNotFound());

        long repositoryB = createRepository(owner, "PUBLIC");
        String mainB = initialize(owner, repositoryB);
        org.assertj.core.api.Assertions.assertThat(mainB).isNotEqualTo(mainA);
        mvc.perform(post(branchesA).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"cross-repository\",\"fromCommitHash\":\"" + mainB + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsPublicBranchesAnonymouslyAndHidesPrivateBranchesFromNonOwners() throws Exception {
        TestAccount owner = register("branchvisibility");
        TestAccount other = register("branchviewer");
        long publicId = createRepository(owner, "PUBLIC");
        String publicMain = initialize(owner, publicId);
        mvc.perform(post("/api/repositories/" + publicId + "/branches").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"feature/z\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/repositories/" + publicId + "/branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("feature/z"))
                .andExpect(jsonPath("$[1].name").value("main"))
                .andExpect(jsonPath("$[1].headCommit.commitHash").value(publicMain));

        long privateId = createRepository(owner, "PRIVATE");
        initialize(owner, privateId);
        mvc.perform(get("/api/repositories/" + privateId + "/branches")).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + privateId + "/branches").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        String privateBranches = mvc.perform(get("/api/repositories/" + privateId + "/branches")
                .header("Authorization", bearer(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("main"))
                .andReturn().getResponse().getContentAsString();
        Number privateBranchId = JsonPath.read(privateBranches, "$[0].id");
        mvc.perform(get("/api/branches/" + privateBranchId.longValue())).andExpect(status().isNotFound());
        mvc.perform(get("/api/branches/" + privateBranchId.longValue()).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/branches/" + privateBranchId.longValue()).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    private long createRepository(TestAccount account, String visibility) throws Exception {
        String name = "branchrepo-" + NAMES.incrementAndGet() + "-" + System.nanoTime();
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(account))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id");
        return id.longValue();
    }

    private String initialize(TestAccount account, long repositoryId) throws Exception {
        String response = mvc.perform(post("/api/repositories/" + repositoryId + "/git/init")
                .header("Authorization", bearer(account)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.commitHash");
    }

    private TestAccount register(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                        + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.user.id");
        return new TestAccount(id.longValue(), JsonPath.read(response, "$.accessToken"));
    }

    private String bearer(TestAccount account) { return "Bearer " + account.token; }

    private record TestAccount(Long id, String token) { }
}
