package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@ActiveProfiles({"test", "operations-test"})
@Import(OperationsIntegrationTest.ProbeController.class)
class OperationsIntegrationTest extends PostgresIntegrationTest {
    @Autowired ApplicationContext context;

    @Test
    void publicHealthAndProbesHideDetailsEvenForAuthenticatedUsers() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            for (String token : new String[] {null, worker, admin}) {
                var body = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", path, token, null, 200));
                assertThat(body.has("components")).isFalse();
                assertThat(body.has("details")).isFalse();
                // Boot lists public probe names on root health; neither name contains diagnostics.
                assertThat(body.size()).isEqualTo(path.equals("/actuator/health") ? 2 : 1);
                if (path.equals("/actuator/health")) {
                    assertThat(body.path("groups").toString()).contains("liveness", "readiness");
                }
                assertThat(body.path("status").asText()).isEqualTo("UP");
            }
        }
    }

    @Test
    void infoRequiresAuditPermissionAndSensitiveEndpointsAreNotExposed() throws Exception {
        assertProblem(executeHttpRequestAndAssertStatus("GET", "/actuator/info", null, null, 401), 401);
        assertProblem(executeHttpRequestAndAssertStatus("GET", "/actuator/info", worker, null, 403), 403);
        var info = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/actuator/info", admin, null, 200));
        assertThat(info.path("app").path("name").asText()).isEqualTo("warehouse-backend");
        for (String endpoint : new String[] {"env", "configprops", "heapdump", "metrics"}) {
            executeHttpRequestAndAssertStatus("GET", "/actuator/" + endpoint, admin, null, 404);
        }
    }

    @Test
    void refusingTrafficFailsReadinessWithoutFailingLiveness() throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            executeHttpRequestAndAssertStatus("GET", "/actuator/health/readiness", null, null, 503);
            executeHttpRequestAndAssertStatus("GET", "/actuator/health/liveness", null, null, 200);
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void correlationIdIsEchoedAvailableInMdcAndClearedAfterSuccessOrFailure() throws Exception {
        var success = mvc.perform(get("/test/operations/trace")
                .header("Authorization", "Bearer " + admin)
                .header("X-Correlation-ID", "interview-request-42")).andReturn();
        assertThat(success.getResponse().getStatus()).isEqualTo(200);
        assertThat(success.getResponse().getHeader("X-Correlation-ID")).isEqualTo("interview-request-42");
        assertThat(parseHttpResponseJson(success).path("correlationId").asText()).isEqualTo("interview-request-42");
        assertThat(MDC.get("correlationId")).isNull();

        var failure = mvc.perform(get("/test/operations/failure")
                .header("Authorization", "Bearer " + admin)
                .header("X-Correlation-ID", "failure-42")).andReturn();
        assertProblem(failure, 500);
        assertThat(parseHttpResponseJson(failure).path("correlationId").asText()).isEqualTo("failure-42");
        assertThat(failure.getResponse().getContentAsString()).doesNotContain("secret-internal-sentinel");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void invalidOrMissingCorrelationIdsAreReplacedAndSecurityErrorsShareTheId() throws Exception {
        for (String value : new String[] {"unsafe value", "a".repeat(65), ""}) {
            var result = mvc.perform(get("/api/v1/products").header("X-Correlation-ID", value)).andReturn();
            assertProblem(result, 401);
            assertThat(result.getResponse().getHeader("X-Correlation-ID"))
                    .matches("[a-f0-9-]{36}").isNotEqualTo(value);
            assertThat(MDC.get("correlationId")).isNull();
        }
        var first = executeHttpRequestAndAssertStatus("GET", "/api/v1/products", null, null, 401);
        var second = executeHttpRequestAndAssertStatus("GET", "/api/v1/products", null, null, 401);
        assertThat(first.getResponse().getHeader("X-Correlation-ID"))
                .isNotBlank().isNotEqualTo(second.getResponse().getHeader("X-Correlation-ID"));
    }

    @Test
    void validationNotFoundAndConflictUseTheSameSafeProblemContract() throws Exception {
        assertProblem(executeHttpRequestAndAssertStatus("POST", "/api/v1/contact", null,
                Map.of("name", "Visitor", "email", "invalid", "subject", "Hello", "message", "Too short"), 400), 400);
        assertProblem(executeHttpRequestAndAssertStatus("GET", "/api/v1/products/9223372036854775807", admin, null, 404), 404);
        assertProblem(executeHttpRequestAndAssertStatus("POST", "/api/v1/categories", admin,
                Map.of("code", "C-" + suffix, "name", "Duplicate", "active", true), 409), 409);
    }

    @Test
    void nullScopeElementIsBadRequestAndPreservesExistingWarehouseAccess() throws Exception {
        var result = mvc.perform(put("/api/v1/admin/users/" + workerId + "/warehouse-scopes")
                .header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"scopes\":[null]}"))
                .andReturn();
        assertProblem(result, 400);
        assertThat(jdbc.queryForObject("select count(*) from security_user_warehouse_scope where user_id=? and warehouse_id=?",
                Integer.class, workerId, north)).isEqualTo(1);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses/" + north, worker, null, 200);
    }

    private void assertProblem(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        var body = parseHttpResponseJson(result);
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("title").asText()).isNotBlank();
        assertThat(body.path("detail").asText()).isNotBlank();
        assertThat(body.path("instance").asText()).isEqualTo(result.getRequest().getRequestURI());
        assertThat(body.path("timestamp").asText()).isNotBlank();
        assertThat(body.path("correlationId").asText()).isEqualTo(result.getResponse().getHeader("X-Correlation-ID"));
        assertThat(body.has("trace")).isFalse();
    }

    // Only loaded by this test profile: exercises the real filter/advice chain on a 500.
    @RestController
    @Profile("operations-test")
    static class ProbeController {
        @GetMapping("/test/operations/trace")
        Map<String, String> trace() {
            return Map.of("correlationId", MDC.get("correlationId"));
        }

        @GetMapping("/test/operations/failure")
        void fail() {
            throw new IllegalStateException("secret-internal-sentinel");
        }
    }
}
