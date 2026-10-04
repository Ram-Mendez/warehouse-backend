package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Duration;
import java.util.Map;

class AuthIntegrationTest extends PostgresIntegrationTest {
    @Autowired javax.sql.DataSource dataSource;

    @Test
    void loginWaitsForConcurrentPasswordChangeBeforeCheckingCredentials() throws Exception {
        String replacement =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
                        .encode("Replacement-password-2026!");
        try (var connection = dataSource.getConnection();
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "update security_user set password_hash=? where id=?")) {
                statement.setString(1, replacement);
                statement.setLong(2, workerId);
                statement.executeUpdate();
            }
            var entered = new java.util.concurrent.CountDownLatch(1);
            var login =
                    pool.submit(
                            () -> {
                                entered.countDown();
                                return mvc.perform(
                                                org.springframework.test.web.servlet.request
                                                        .MockMvcRequestBuilders.post(
                                                                "/api/v1/auth/login")
                                                        .contentType("application/json")
                                                        .content(
                                                                mapper.writeValueAsString(
                                                                        Map.of(
                                                                                "username",
                                                                                workerName,
                                                                                "password",
                                                                                PASSWORD))))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            boolean waiting = false;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                waiting =
                        Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "select exists(select 1 from pg_stat_activity where"
                                            + " wait_event_type='Lock' and query like 'select *"
                                            + " from security_user where username=%for update')",
                                        Boolean.class));
                if (waiting) {
                    break;
                }
                Thread.sleep(20);
            }
            connection.commit();
            connection.setAutoCommit(true);
            assertThat(waiting).as("Login waits on the account row lock").isTrue();
            assertThat(login.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(401);
        }
    }

    @Autowired JwtDecoder decoder;

    @Test
    void loginIssuesFortyFiveMinuteJwtAndHashOnlyRefresh() throws Exception {
        var tokens = login(adminName);
        String raw = tokens.path("refreshToken").asText();
        assertThat(java.util.Base64.getUrlDecoder().decode(raw)).hasSize(48);
        var jwt = decoder.decode(tokens.path("accessToken").asText());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(45));
        assertThat(jwt.getClaimAsStringList("permissions")).contains("PERM_INVENTORY_READ");
        assertThat(
                        jdbc.queryForList(
                                "select token_hash from auth_refresh_token where"
                                        + " session_id=?::uuid",
                                String.class,
                                jwt.getClaimAsString("sid")))
                .allMatch(hash -> hash.matches("[0-9a-f]{64}") && !hash.equals(raw));
        call("GET", "/api/v1/auth/me", tokens.path("accessToken").asText(), null, 200);
    }

    @Test
    void badPasswordAndMissingUserAreUnauthorized() throws Exception {
        call(
                "POST",
                "/api/v1/auth/login",
                null,
                Map.of("username", adminName, "password", "bad-password"),
                401);
        call(
                "POST",
                "/api/v1/auth/login",
                null,
                Map.of("username", "absent", "password", PASSWORD),
                401);
    }

    @Test
    void protectedEndpointRequiresJwt() throws Exception {
        call("GET", "/api/v1/products", null, null, 401);
        call("GET", "/api/v1/products", "bad.jwt.value", null, 401);
    }

    @Test
    void refreshRotatesTwiceAndConsumesParents() throws Exception {
        var r1 = login(adminName);
        var r2 =
                json(
                        call(
                                "POST",
                                "/api/v1/auth/refresh",
                                null,
                                Map.of("refreshToken", r1.path("refreshToken").asText()),
                                200));
        var r3 =
                json(
                        call(
                                "POST",
                                "/api/v1/auth/refresh",
                                null,
                                Map.of("refreshToken", r2.path("refreshToken").asText()),
                                200));
        assertThat(r3.path("refreshToken").asText()).isNotEqualTo(r2.path("refreshToken").asText());
        String sid = decoder.decode(r1.path("accessToken").asText()).getClaimAsString("sid");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_refresh_token where session_id=?::uuid"
                                        + " and used_at is not null",
                                Integer.class,
                                sid))
                .isEqualTo(2);
    }

    @Test
    void reuseRevokesCompleteFamilyAndCommitsBeforeUnauthorized() throws Exception {
        var r1 = login(adminName);
        var r2 =
                json(
                        call(
                                "POST",
                                "/api/v1/auth/refresh",
                                null,
                                Map.of("refreshToken", r1.path("refreshToken").asText()),
                                200));
        var r3 =
                json(
                        call(
                                "POST",
                                "/api/v1/auth/refresh",
                                null,
                                Map.of("refreshToken", r2.path("refreshToken").asText()),
                                200));
        call(
                "POST",
                "/api/v1/auth/refresh",
                null,
                Map.of("refreshToken", r1.path("refreshToken").asText()),
                401);
        call(
                "POST",
                "/api/v1/auth/refresh",
                null,
                Map.of("refreshToken", r3.path("refreshToken").asText()),
                401);
        String sid = decoder.decode(r1.path("accessToken").asText()).getClaimAsString("sid");
        assertThat(
                        jdbc.queryForObject(
                                "select revoked_at is not null from auth_session where id=?::uuid",
                                Boolean.class,
                                sid))
                .isTrue();
    }

    @Test
    void logoutRevokesRefreshButAccessNaturallyExpires() throws Exception {
        var tokens = login(adminName);
        String access = tokens.path("accessToken").asText();
        call("POST", "/api/v1/auth/logout", access, null, 204);
        call(
                "POST",
                "/api/v1/auth/refresh",
                null,
                Map.of("refreshToken", tokens.path("refreshToken").asText()),
                401);
        call("GET", "/api/v1/auth/me", access, null, 200);
    }

    @Test
    void disabledAndLockedAccountsCannotAuthenticateOrUseOldJwt() throws Exception {
        jdbc.update("update security_user set enabled=false where id=?", workerId);
        call(
                "POST",
                "/api/v1/auth/login",
                null,
                Map.of("username", workerName, "password", PASSWORD),
                401);
        call("GET", "/api/v1/products", worker, null, 401);
        jdbc.update("update security_user set locked=true where id=?", managerId);
        call("GET", "/api/v1/products", manager, null, 401);
    }

    @Test
    void expiredAndForeignIssuerJwtAreUnauthorized() throws Exception {
        var key = decoder.decode(admin);
        // An expired token and wrong issuer are signed through the configured encoder in a separate
        // helper.
        call(
                "GET",
                "/api/v1/auth/me",
                signed(
                        key.getSubject(),
                        "warehouse-backend",
                        java.time.Instant.now().minusSeconds(120)),
                null,
                401);
        call(
                "GET",
                "/api/v1/auth/me",
                signed(key.getSubject(), "other-issuer", java.time.Instant.now().plusSeconds(100)),
                null,
                401);
    }

    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;

    @Test
    void refreshAndPublicContactIgnoreExpiredAccessHeader() throws Exception {
        var tokens = login(adminName);
        String expired =
                signed(
                        Long.toString(adminId),
                        "warehouse-backend",
                        java.time.Instant.now().minusSeconds(120));
        call(
                "POST",
                "/api/v1/auth/refresh",
                expired,
                Map.of("refreshToken", tokens.path("refreshToken").asText()),
                200);
        call(
                "POST",
                "/api/v1/contact",
                expired,
                Map.of(
                        "name",
                        "Public visitor",
                        "email",
                        "public@example.test",
                        "subject",
                        "Question",
                        "message",
                        "A valid public contact question."),
                201);
    }

    @Test
    void concurrentRefreshHasOneSuccessAndReuseRevokesWinner() throws Exception {
        var tokens = login(adminName);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<org.springframework.test.web.servlet.MvcResult> request =
                    () -> {
                        ready.countDown();
                        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Start timeout");
                        }
                        return mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.post("/api/v1/auth/refresh")
                                                .contentType("application/json")
                                                .content(
                                                        mapper.writeValueAsString(
                                                                Map.of(
                                                                        "refreshToken",
                                                                        tokens.path("refreshToken")
                                                                                .asText()))))
                                .andReturn();
                    };
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(a.getResponse().getStatus(), b.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);
            var winner = json(a.getResponse().getStatus() == 200 ? a : b);
            call(
                    "POST",
                    "/api/v1/auth/refresh",
                    null,
                    Map.of("refreshToken", winner.path("refreshToken").asText()),
                    401);
        }
    }

    private String signed(String subject, String issuer, java.time.Instant expires) {
        var claims =
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                        .subject(subject)
                        .issuer(issuer)
                        .issuedAt(java.time.Instant.now().minusSeconds(500))
                        .expiresAt(expires)
                        .claim("av", 0)
                        .build();
        return encoder.encode(
                        org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                                org.springframework.security.oauth2.jwt.JwsHeader.with(
                                                org.springframework.security.oauth2.jose.jws
                                                        .MacAlgorithm.HS256)
                                        .build(),
                                claims))
                .getTokenValue();
    }
}
