package com.versioncontrol;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class RepositoryPlatformIntegrationTests {
    @Autowired MockMvc mvc;

    @Test void createAssignsOwnerFromJwtAndValidatesUniquenessAndInput() throws Exception {
        TestAccount alice = register("repoa");
        TestAccount bob = register("repob");
        String publicBody = "{\"name\":\"shared-name\",\"description\":\"public\",\"visibility\":\"PUBLIC\",\"ownerId\":" + bob.id + ",\"id\":999}";
        var created = mvc.perform(post("/api/repositories").header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON).content(publicBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.owner.id").value(alice.id))
                .andExpect(jsonPath("$.owner.username").exists()).andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Number createdId = JsonPath.read(created, "$.id");
        long repositoryId = createdId.longValue();
        mvc.perform(post("/api/repositories").header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"shared-name\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/repositories").header("Authorization", bearer(bob)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"shared-name\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/repositories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"anonymous\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/repositories").header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad\",\"visibility\":\"UNLISTED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/" + repositoryId)).andExpect(status().isOk());
    }

    @Test void publicAndPrivateReadsAndMineAreScopedToAuthenticatedUser() throws Exception {
        TestAccount alice = register("readA");
        TestAccount bob = register("readB");
        long publicId = create(alice, "alice-public", "PUBLIC");
        long privateId = create(alice, "alice-private", "PRIVATE");
        create(bob, "bob-private", "PRIVATE");

        mvc.perform(get("/api/repositories/" + publicId)).andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PUBLIC"));
        mvc.perform(get("/api/repositories/" + privateId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + privateId).header("Authorization", bearer(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + privateId).header("Authorization", bearer(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/repositories/" + publicId).header("Authorization", bearer(bob))).andExpect(status().isOk());
        mvc.perform(get("/api/repositories")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("alice-public")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alice-private"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bob-private"))));
        mvc.perform(get("/api/repositories").param("owner", alice.username).param("name", "public")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("alice-public")));
        mvc.perform(get("/api/repositories/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/repositories/mine").header("Authorization", bearer(alice))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("alice-private")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bob-private"))));
        mvc.perform(get("/api/repositories/999999999")).andExpect(status().isNotFound());
    }

    @Test void idorUpdateAndDeleteAreDeniedAndOwnerCanUpdateAndDelete() throws Exception {
        TestAccount alice = register("ownerA");
        TestAccount bob = register("ownerB");
        long ownedId = create(alice, "owned-repo", "PRIVATE");
        long conflictId = create(alice, "already-used", "PUBLIC");
        String update = "{\"name\":\"updated-repo\",\"description\":\"updated\",\"visibility\":\"PUBLIC\",\"ownerId\":" + bob.id + "}";

        mvc.perform(put("/api/repositories/" + ownedId).contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/repositories/" + ownedId).header("Authorization", bearer(bob)).contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/repositories/" + ownedId).header("Authorization", bearer(bob))).andExpect(status().isForbidden());
        mvc.perform(put("/api/repositories/" + ownedId).header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"already-used\",\"description\":null,\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/repositories/" + ownedId).header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.owner.id").value(alice.id)).andExpect(jsonPath("$.name").value("updated-repo"));
        mvc.perform(delete("/api/repositories/" + ownedId)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/repositories/" + ownedId).header("Authorization", bearer(alice))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/repositories/" + ownedId).header("Authorization", bearer(alice))).andExpect(status().isNotFound());
        mvc.perform(put("/api/repositories/999999999").header("Authorization", bearer(alice)).contentType(MediaType.APPLICATION_JSON)
                .content(update)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/repositories/999999999").header("Authorization", bearer(alice))).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/" + conflictId).header("Authorization", bearer(alice))).andExpect(status().isOk());
    }

    private long create(TestAccount account, String name, String visibility) throws Exception {
        String response = mvc.perform(post("/api/repositories").header("Authorization", bearer(account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number repositoryId = JsonPath.read(response, "$.id");
        return repositoryId.longValue();
    }

    private TestAccount register(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}";
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number userId = JsonPath.read(response, "$.user.id");
        return new TestAccount(userId.longValue(), username, JsonPath.read(response, "$.accessToken"));
    }

    private String bearer(TestAccount account) { return "Bearer " + account.token; }
    private record TestAccount(Long id, String username, String token) { }
}
