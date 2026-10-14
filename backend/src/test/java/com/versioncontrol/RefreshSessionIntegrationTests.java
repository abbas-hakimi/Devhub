package com.versioncontrol;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.versioncontrol.entity.RefreshSessionRevocationReason;
import com.versioncontrol.repository.RefreshSessionRepository;
import com.versioncontrol.repository.RefreshTokenHistoryRepository;
import com.versioncontrol.repository.UserRepository;
import com.versioncontrol.service.RefreshSessionService;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class RefreshSessionIntegrationTests {
    private static final String ORIGIN = "http://localhost:3000";
    private static final String COOKIE_NAME = "DEVHUB_REFRESH";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RefreshSessionRepository sessions;
    @Autowired RefreshTokenHistoryRepository tokenHistory;
    @Autowired RefreshSessionService refreshSessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;

    @Test
    void loginSetsHostOnlyHttpOnlyRefreshCookieAndRotationDetectsReplay() throws Exception {
        String username = register();
        Login login = login(username);
        assertNotNull(login.refreshCookie());
        assertEquals(ORIGIN, mvc.perform(options("/api/auth/refresh")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Access-Control-Allow-Origin"));

        MvcResult rotated = mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN)
                        .cookie(login.refreshCookie()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();
        Cookie newCookie = cookieFrom(rotated);
        assertNotNull(newCookie);
        assertNotEquals(login.refreshCookie().getValue(), newCookie.getValue());

        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN)
                        .cookie(login.refreshCookie()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(newCookie))
                .andExpect(status().isUnauthorized());

        var session = findSession(username);
        assertEquals(RefreshSessionRevocationReason.TOKEN_REPLAY, session.getRevocationReason());
    }

    @Test
    void logoutRevokesOnlyCookieSessionAndSessionEndpointsAreUserScoped() throws Exception {
        String firstUser = register();
        String secondUser = register();
        Login firstLogin = login(firstUser);
        Login secondLogin = login(secondUser);

        MvcResult firstSessions = mvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + firstLogin.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].sessionId").isNumber()).andReturn();
        long firstSessionId = mapper.readTree(firstSessions.getResponse().getContentAsString()).get(0).get("sessionId").asLong();
        mvc.perform(delete("/api/auth/sessions/" + firstSessionId)
                        .header("Authorization", "Bearer " + secondLogin.accessToken()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/auth/sessions/" + firstSessionId)
                        .header("Authorization", "Bearer " + firstLogin.accessToken()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(firstLogin.refreshCookie()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(secondLogin.refreshCookie()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").header("Origin", ORIGIN).cookie(secondLogin.refreshCookie()))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(secondLogin.refreshCookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllRevokesAllSessionsAndUntrustedOrMissingOriginsAreRejected() throws Exception {
        String username = register();
        Login first = login(username);
        Login second = login(username);
        mvc.perform(post("/api/auth/logout-all").header("Origin", ORIGIN)
                        .header("Authorization", "Bearer " + first.accessToken()).cookie(first.refreshCookie()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(first.refreshCookie()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(second.refreshCookie()))
                .andExpect(status().isUnauthorized());

        Login another = login(register());
        mvc.perform(post("/api/auth/refresh").header("Origin", "https://attacker.example")
                        .cookie(another.refreshCookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(another.refreshCookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(another.refreshCookie()))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentRefreshSerializesAndReplayRevokesTheFamily() throws Exception {
        String username = register();
        Login login = login(username);
        String raw = login.refreshCookie().getValue();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> { await(start); return refreshSessions.rotate(raw); });
            Future<?> second = executor.submit(() -> { await(start); return refreshSessions.rotate(raw); });
            start.countDown();
            var firstResult = (com.versioncontrol.service.RefreshRotationResult) first.get(20, TimeUnit.SECONDS);
            var secondResult = (com.versioncontrol.service.RefreshRotationResult) second.get(20, TimeUnit.SECONDS);
            assertEquals(1, (firstResult.successful() ? 1 : 0) + (secondResult.successful() ? 1 : 0));
            var session = findSession(username);
            assertEquals(RefreshSessionRevocationReason.TOKEN_REPLAY, session.getRevocationReason());
            String replacement = firstResult.successful() ? firstResult.replacementToken() : secondResult.replacementToken();
            org.junit.jupiter.api.Assertions.assertFalse(refreshSessions.rotate(replacement).successful());
            assertEquals(2, tokenHistory.findAll().stream()
                    .filter(token -> token.getSession().getId().equals(session.getId())).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLogoutAndRefreshCannotLeaveSessionActiveAndOnlyHashesAreStored() throws Exception {
        String username = register();
        Login login = login(username);
        String raw = login.refreshCookie().getValue();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<?> refresh = executor.submit(() -> { await(start); return refreshSessions.rotate(raw); });
            Future<?> logout = executor.submit(() -> { await(start); refreshSessions.logout(raw); return null; });
            start.countDown();
            var result = (com.versioncontrol.service.RefreshRotationResult) refresh.get(20, TimeUnit.SECONDS);
            logout.get(20, TimeUnit.SECONDS);
            var session = findSession(username);
            org.junit.jupiter.api.Assertions.assertNotNull(session.getRevokedAt());
            var histories = tokenHistory.findAll().stream()
                    .filter(token -> token.getSession().getId().equals(session.getId())).toList();
            assertEquals(result.successful() ? 2 : 1, histories.size());
            org.junit.jupiter.api.Assertions.assertTrue(histories.stream().noneMatch(token -> token.getTokenHash().equals(raw)));
            if (result.successful()) org.junit.jupiter.api.Assertions.assertFalse(
                    refreshSessions.rotate(result.replacementToken()).successful());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredRefreshTokenIsRejectedAndSessionIsMarkedExpired() throws Exception {
        String username = register();
        Login login = login(username);
        String raw = login.refreshCookie().getValue();
        var hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tokenHash = java.util.HexFormat.of().formatHex(hash);
        jdbc.update("UPDATE refresh_token_history SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE token_hash = ?", tokenHash);
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(login.refreshCookie()))
                .andExpect(status().isUnauthorized());
        var session = findSession(username);
        assertEquals(RefreshSessionRevocationReason.EXPIRED, session.getRevocationReason());
    }

    private String register() throws Exception {
        String username = "refresh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.test\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isCreated());
        return username;
    }

    private Login login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + username + "\",\"password\":\"StrongPass123\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/auth")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie", not(containsString("Domain="))))
                .andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        Cookie refresh = cookieFrom(result);
        return new Login(body.get("accessToken").asText(), refresh);
    }

    private Cookie cookieFrom(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(header);
        String prefix = COOKIE_NAME + "=";
        String pair = java.util.Arrays.stream(header.split(";"))
                .map(String::trim).filter(part -> part.startsWith(prefix)).findFirst().orElseThrow();
        return new Cookie(COOKIE_NAME, pair.substring(prefix.length()));
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private com.versioncontrol.entity.RefreshSession findSession(String username) {
        Long userId = users.findByUsernameIgnoreCase(username).orElseThrow().getId();
        return sessions.listForUser(userId).stream().findFirst().orElseThrow();
    }

    private record Login(String accessToken, Cookie refreshCookie) { }
}
