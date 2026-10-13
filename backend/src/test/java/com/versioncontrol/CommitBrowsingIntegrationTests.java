package com.versioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.repository.BranchRepository;
import com.versioncontrol.versioncontrol.hash.Sha256Hasher;

@SpringBootTest
@AutoConfigureMockMvc
class CommitBrowsingIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired BranchRepository branches;

    @Test
    void publicCommitHistoryTreePathsAndBlobStreamingWork() throws Exception {
        Account owner = register("browseowner");
        long repositoryId = createRepository(owner, "PUBLIC");
        initialize(owner, repositoryId);
        String initial = head(repositoryId);
        commit(owner, repositoryId, "First files", "[{\"path\":\"README.md\",\"content\":\"hello\"},"
                + "{\"path\":\"z.bin\",\"contentBase64\":\"AAEC/w==\"},"
                + "{\"path\":\"src/utils/file.txt\",\"content\":\"nested\"},"
                + "{\"path\":\"\uE000.txt\",\"content\":\"bmp\"},"
                + "{\"path\":\"😀.txt\",\"content\":\"supplementary\"}]");
        String first = head(repositoryId);
        commit(owner, repositoryId, "Second", "[{\"path\":\"another.txt\",\"content\":\"latest\"}]");
        String second = head(repositoryId);

        mvc.perform(get("/api/repositories/{id}/commits", repositoryId).param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commits.length()").value(3))
                .andExpect(jsonPath("$.commits[0].commitHash").value(second))
                .andExpect(jsonPath("$.commits[1].commitHash").value(first))
                .andExpect(jsonPath("$.commits[2].commitHash").value(initial))
                .andExpect(jsonPath("$.commits[0].parentCommitHash").value(first));
        mvc.perform(get("/api/repositories/{id}/commits", repositoryId).param("branch", "missing"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/{id}/commits", 99999999L)).andExpect(status().isNotFound());

        MvcResult rootResult = mvc.perform(get("/api/commits/{hash}/tree", first))
                .andExpect(status().isOk()).andReturn();
        List<String> names = JsonPath.read(rootResult.getResponse().getContentAsString(), "$.entries[*].name");
        assertThat(names).containsExactly("README.md", "src", "z.bin", "\uE000.txt", "😀.txt");
        List<Map<String, Object>> rootEntries = JsonPath.read(rootResult.getResponse().getContentAsString(), "$.entries");
        Map<String, Object> readmeEntry = rootEntries.stream().filter(e -> e.get("name").equals("README.md")).findFirst().orElseThrow();
        Map<String, Object> srcEntry = rootEntries.stream().filter(e -> e.get("name").equals("src")).findFirst().orElseThrow();
        assertThat(readmeEntry).containsEntry("type", "FILE").containsKey("blobHash").containsEntry("size", 5);
        assertThat(srcEntry).containsEntry("type", "DIRECTORY").containsKey("treeHash");
        mvc.perform(get("/api/commits/{hash}/tree/src", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src"))
                .andExpect(jsonPath("$.entries[0].name").value("utils"));
        mvc.perform(get("/api/commits/{hash}/tree/src/utils", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].name").value("file.txt"));
        mvc.perform(get("/api/commits/{hash}/tree/src/utils/file.txt", first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/commits/{hash}/tree/nope", first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/commits/{hash}/tree/%2E%2E", first)).andExpect(status().isBadRequest());

        byte[] text = "hello".getBytes(StandardCharsets.UTF_8);
        String textHash = Sha256Hasher.hashBytes(text);
        MvcResult textStart = mvc.perform(get("/api/commits/{hash}/blob/README.md", first)).andReturn();
        MvcResult textResult = textStart.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(textStart)).andReturn() : textStart;
        assertThat(textResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(textResult.getResponse().getContentAsByteArray()).containsExactly(text);
        assertThat(textResult.getResponse().getContentLength()).isEqualTo(text.length);
        assertThat(textResult.getResponse().getHeader("ETag")).isEqualTo('"' + textHash + '"');
        mvc.perform(get("/api/commits/{hash}/blob/README.md", first).header("If-None-Match", '"' + textHash + '"'))
                .andExpect(status().isNotModified());
        MvcResult binaryStart = mvc.perform(get("/api/commits/{hash}/blob/z.bin", first)).andReturn();
        MvcResult binaryResult = binaryStart.getRequest().isAsyncStarted()
                ? mvc.perform(asyncDispatch(binaryStart)).andReturn() : binaryStart;
        assertThat(binaryResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(binaryResult.getResponse().getContentAsByteArray()).containsExactly((byte) 0, (byte) 1, (byte) 2, (byte) -1);
        mvc.perform(get("/api/commits/{hash}/blob/src", first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/commits/{hash}/blob/nope", first)).andExpect(status().isNotFound());
        // MockMvc exercises the application path validator directly. A real HTTP request
        // with the same encoded dot segment may instead be stopped by Spring Security's
        // authenticated fallback before the controller is reached.
        mvc.perform(get("/api/commits/{hash}/blob/%2E%2E/secret", first))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/commits/{hash}/blob/src%5C..%5Csecret", first)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/commits/{hash}/blob/C:%5Csecret", first)).andExpect(status().isBadRequest());

        mvc.perform(get("/api/commits/{hash}", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value(repositoryId))
                .andExpect(jsonPath("$.commitHash").value(first));
        mvc.perform(get("/api/commits/{hash}", "f".repeat(64))).andExpect(status().isNotFound());
    }

    @Test
    void privateHistoryAndCommitDataAreHiddenFromAnonymousAndOtherUsers() throws Exception {
        Account owner = register("privatebrowseowner");
        Account other = register("privatebrowseother");
        long repositoryId = createRepository(owner, "PRIVATE");
        initialize(owner, repositoryId);
        commit(owner, repositoryId, "Private content", "[{\"path\":\"secret.txt\",\"content\":\"private\"}]");
        String hash = head(repositoryId);
        mvc.perform(get("/api/repositories/{id}/commits", repositoryId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/{id}/commits", repositoryId).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/commits/{hash}", hash)).andExpect(status().isNotFound());
        mvc.perform(get("/api/commits/{hash}", hash).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/commits/{hash}/tree", hash).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/commits/{hash}/blob/secret.txt", hash).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/{id}/commits", repositoryId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    @Test
    void branchHistoryStartsAtItsOwnHeadAndPaginationIsBounded() throws Exception {
        Account owner = register("branchbrowse");
        long repositoryId = createRepository(owner, "PUBLIC");
        initialize(owner, repositoryId);
        String initial = head(repositoryId);
        mvc.perform(post("/api/repositories/{id}/branches", repositoryId).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"feature\"}"))
                .andExpect(status().isCreated());
        commit(owner, repositoryId, "Advance main", "[{\"path\":\"main.txt\",\"content\":\"main\"}]");
        mvc.perform(get("/api/repositories/{id}/commits", repositoryId).param("branch", "feature"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commits[0].commitHash").value(initial));
        mvc.perform(get("/api/repositories/{id}/commits", repositoryId).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/{id}/commits", repositoryId).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/{id}/commits", repositoryId).param("page", "10").param("size", "100"))
                .andExpect(status().isBadRequest());
    }

    private void commit(Account owner, long repositoryId, String message, String changes) throws Exception {
        mvc.perform(post("/api/repositories/{id}/commits", repositoryId).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"main\",\"message\":\"" + message + "\",\"changes\":" + changes + "}"))
                .andExpect(status().isCreated());
    }

    private String head(long repositoryId) {
        Branch branch = branches.findByRepository_IdAndName(repositoryId, "main").orElseThrow();
        return branch.getHeadCommit().getCommitHash();
    }

    private void initialize(Account owner, long repositoryId) throws Exception {
        mvc.perform(post("/api/repositories/{id}/git/init", repositoryId).header("Authorization", bearer(owner)))
                .andExpect(status().isCreated());
    }

    private long createRepository(Account owner, String visibility) throws Exception {
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"browse-" + System.nanoTime()
                        + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private Account register(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                        + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(response, "$.user.id")).longValue(), JsonPath.read(response, "$.accessToken"));
    }

    private String bearer(Account account) { return "Bearer " + account.token(); }
    private record Account(long id, String token) { }
}
