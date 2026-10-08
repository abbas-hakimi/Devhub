package com.versioncontrol;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.versioncontrol.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    @Test void registrationHashesPasswordAndReturnsSafeUserAndToken() throws Exception {
        String username = "auth_" + System.nanoTime();
        String email = username + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"StrongPass123\",\"displayName\":\"Test User\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist()).andExpect(jsonPath("$.user.password").doesNotExist());
        var saved = users.findByUsernameIgnoreCase(username).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals("StrongPass123", saved.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getPasswordHash().startsWith("$2"));
    }

    @Test void rejectsInvalidRegistrationAndDuplicateUsername() throws Exception {
        String username = "dupe_" + System.nanoTime();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\",\"password\":\"StrongPass123\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\",\"email\":\"bad\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields").exists());
    }

    @Test void loginByUsernameAndEmailAndMeRequireValidToken() throws Exception {
        String username = "login_" + System.nanoTime();
        String email = username + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated());
        String loginBody = "{\"identifier\":\"" + email + "\",\"password\":\"StrongPass123\"}";
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(response, "$.accessToken");
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        char replacement = original == 'a' ? 'b' : 'a';

        String changedSignature =
        token.substring(0, signatureStart)
        + replacement
        + token.substring(signatureStart + 1);   
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + changedSignature)).andExpect(status().isUnauthorized());
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));
        String expired = io.jsonwebtoken.Jwts.builder().subject(String.valueOf(users.findByUsernameIgnoreCase(username).orElseThrow().getId()))
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 120_000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 60_000))
                .signWith(key, io.jsonwebtoken.Jwts.SIG.HS256).compact();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        String wrongPassword = "{\"identifier\":\"" + username + "\",\"password\":\"WrongPass123\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrongPassword))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error", not(containsString("passwordHash"))));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer malformed.token.value")).andExpect(status().isUnauthorized());
    }

    @Test void healthIsPublicAndOtherRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/private-check")).andExpect(status().isUnauthorized());
    }
}
