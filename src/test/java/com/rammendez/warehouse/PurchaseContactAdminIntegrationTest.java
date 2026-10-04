package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

class PurchaseContactAdminIntegrationTest extends PostgresIntegrationTest {
    private String purchase() throws Exception {
        var order =
                json(
                        call(
                                "POST",
                                "/api/v1/purchase-orders",
                                manager,
                                Map.of(
                                        "supplierId",
                                        supplier,
                                        "warehouseId",
                                        north,
                                        "orderNumber",
                                        "PO-" + suffix),
                                201));
        String id = order.path("id").asText();
        call(
                "POST",
                "/api/v1/purchase-orders/" + id + "/lines",
                manager,
                Map.of("productId", product, "quantity", 7, "unitCost", 2.5),
                200);
        return id;
    }

    @Test
    void purchaseLifecycleReceiveAndDoubleReceiveProtection() throws Exception {
        String id = purchase();
        call("POST", "/api/v1/purchase-orders/" + id + "/submit", manager, null, 200);
        call("POST", "/api/v1/purchase-orders/" + id + "/approve", manager, null, 200);
        var received =
                json(
                        call(
                                "POST",
                                "/api/v1/purchase-orders/" + id + "/receive",
                                manager,
                                null,
                                200));
        assertThat(received.path("status").asText()).isEqualTo("RECEIVED");
        assertThat(received.path("lines").get(0).path("receivedQuantity").decimalValue())
                .isEqualByComparingTo("7");
        assertThat(balance(north)).isEqualByComparingTo("7");
        call("POST", "/api/v1/purchase-orders/" + id + "/receive", manager, null, 409);
        assertThat(balance(north)).isEqualByComparingTo("7");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stock_movement where"
                                        + " purchase_order_id=?::uuid",
                                Integer.class,
                                id))
                .isEqualTo(1);
        call(
                "GET",
                "/api/v1/purchase-orders?warehouseId=" + north + "&status=RECEIVED",
                worker,
                null,
                200);
    }

    @Test
    void purchaseInvalidTransitionsDuplicatesAndCancellation() throws Exception {
        String id = purchase();
        call("POST", "/api/v1/purchase-orders/" + id + "/receive", manager, null, 409);
        call("POST", "/api/v1/purchase-orders/" + id + "/approve", manager, null, 409);
        call(
                "POST",
                "/api/v1/purchase-orders/" + id + "/lines",
                manager,
                Map.of("productId", product, "quantity", 1),
                409);
        call("POST", "/api/v1/purchase-orders/" + id + "/submit", manager, null, 200);
        call(
                "POST",
                "/api/v1/purchase-orders/" + id + "/lines",
                manager,
                Map.of("productId", product, "quantity", 1),
                409);
        call("POST", "/api/v1/purchase-orders/" + id + "/cancel", manager, null, 200);
        call("POST", "/api/v1/purchase-orders/" + id + "/receive", manager, null, 409);
        assertThat(balance(north)).isZero();
    }

    @Test
    void emptyPurchaseCannotBeSubmitted() throws Exception {
        String id =
                json(call(
                                "POST",
                                "/api/v1/purchase-orders",
                                manager,
                                Map.of(
                                        "supplierId",
                                        supplier,
                                        "warehouseId",
                                        north,
                                        "orderNumber",
                                        "EMPTY-" + suffix),
                                201))
                        .path("id")
                        .asText();
        call("POST", "/api/v1/purchase-orders/" + id + "/submit", manager, null, 409);
    }

    @Test
    void purchasePermissionAndWarehouseScopeAreEnforced() throws Exception {
        String id = purchase();
        call("GET", "/api/v1/purchase-orders/" + id, worker, null, 200);
        call("POST", "/api/v1/purchase-orders/" + id + "/receive", worker, null, 403);
        jdbc.update("delete from security_user_warehouse_scope where user_id=?", workerId);
        call("GET", "/api/v1/purchase-orders/" + id, worker, null, 403);
        assertThat(
                        json(call("GET", "/api/v1/purchase-orders", worker, null, 200))
                                .path("totalElements")
                                .asInt())
                .isZero();
    }

    @Test
    void purchaseReceiptFailureRollsBackStockOrderAndAudit() throws Exception {
        String id = purchase();
        call("POST", "/api/v1/purchase-orders/" + id + "/submit", manager, null, 200);
        BigDecimal max = new BigDecimal("999999999999999.9999");
        jdbc.update(
                "insert into inventory_balance(product_id,warehouse_location_id,quantity) values"
                        + " (?,?,?)",
                product,
                northLocation,
                max);
        call("POST", "/api/v1/purchase-orders/" + id + "/receive", manager, null, 409);
        var unchanged = json(call("GET", "/api/v1/purchase-orders/" + id, manager, null, 200));
        assertThat(unchanged.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(unchanged.path("lines").get(0).path("receivedQuantity").decimalValue()).isZero();
        assertThat(balance(north)).isEqualByComparingTo(max);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stock_movement where"
                                        + " purchase_order_id=?::uuid",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void concurrentPurchaseReceiveStocksExactlyOnce() throws Exception {
        String id = purchase();
        call("POST", "/api/v1/purchase-orders/" + id + "/submit", manager, null, 200);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> request =
                    () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Start timeout");
                        }
                        return mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.post(
                                                        "/api/v1/purchase-orders/"
                                                                + id
                                                                + "/receive")
                                                .header("Authorization", "Bearer " + manager))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(balance(north)).isEqualByComparingTo("7");
    }

    private Map<String, Object> contact() {
        return Map.of(
                "name",
                "Visitor",
                "email",
                "visitor@example.test",
                "subject",
                "Delivery query",
                "message",
                "Please advise on my delivery date.");
    }

    @Test
    void publicContactSubmissionAndAdministrativeStatus() throws Exception {
        var accepted = json(call("POST", "/api/v1/contact", null, contact(), 201));
        String id = accepted.path("id").asText();
        assertThat(accepted.has("message")).isFalse();
        call("GET", "/api/v1/contact/" + id, worker, null, 403);
        call("GET", "/api/v1/contact/" + id, null, null, 401);
        call("GET", "/api/v1/contact/" + id, admin, null, 200);
        var changed =
                json(
                        call(
                                "PATCH",
                                "/api/v1/contact/" + id + "/status",
                                admin,
                                Map.of("status", "RESOLVED"),
                                200));
        assertThat(changed.path("resolvedAt").isNull()).isFalse();
        call("GET", "/api/v1/contact", admin, null, 200);
        assertThat(
                        jdbc.queryForObject(
                                "select event_data::text from audit_event where"
                                        + " entity_type='contact_message' and entity_id=?",
                                String.class,
                                id))
                .isEqualTo("{}");
    }

    @Test
    void publicContactRejectsInvalidEmailAndShortMessage() throws Exception {
        var invalid = new HashMap<>(contact());
        invalid.put("email", "broken");
        call("POST", "/api/v1/contact", null, invalid, 400);
        invalid = new HashMap<>(contact());
        invalid.put("message", "tiny");
        call("POST", "/api/v1/contact", null, invalid, 400);
        invalid = new HashMap<>(contact());
        invalid.put("name", " ");
        call("POST", "/api/v1/contact", null, invalid, 400);
    }

    @Test
    void adminCreatesUserAssignsRolesAndScopesWithoutReturningPasswords() throws Exception {
        String username = "new-" + suffix;
        var user =
                json(
                        call(
                                "POST",
                                "/api/v1/admin/users",
                                admin,
                                Map.of(
                                        "username",
                                        username,
                                        "email",
                                        username + "@example.test",
                                        "password",
                                        PASSWORD),
                                201));
        long id = user.path("id").asLong();
        assertThat(user.has("password")).isFalse();
        assertThat(user.has("passwordHash")).isFalse();
        call(
                "PUT",
                "/api/v1/admin/users/" + id + "/roles",
                admin,
                Map.of("roles", List.of("ROLE_OPERATOR")),
                200);
        call(
                "PUT",
                "/api/v1/admin/users/" + id + "/warehouse-scopes",
                admin,
                Map.of("scopes", List.of(Map.of("warehouseId", north, "scopeRole", "OPERATOR"))),
                200);
        String access = login(username).path("accessToken").asText();
        call("GET", "/api/v1/warehouses/" + north, access, null, 200);
        call("GET", "/api/v1/warehouses/" + south, access, null, 403);
        call("GET", "/api/v1/admin/users", access, null, 403);
        call("GET", "/api/v1/admin/users/" + id, admin, null, 200);
        call("GET", "/api/v1/admin/roles", admin, null, 200);
        assertThat(
                        jdbc.queryForObject(
                                "select password_hash from security_user where id=?",
                                String.class,
                                id))
                .startsWith("$2");
    }

    @Test
    void accountDisableInvalidatesExistingJwtAndRefresh() throws Exception {
        var tokens = login(workerName);
        call("PATCH", "/api/v1/admin/users/" + workerId, admin, Map.of("enabled", false), 200);
        call("GET", "/api/v1/products", tokens.path("accessToken").asText(), null, 401);
        call(
                "POST",
                "/api/v1/auth/refresh",
                null,
                Map.of("refreshToken", tokens.path("refreshToken").asText()),
                401);
    }

    @Test
    void invalidRoleAndScopeReplacementAreAtomic() throws Exception {
        call(
                "PUT",
                "/api/v1/admin/users/" + workerId + "/roles",
                admin,
                Map.of("roles", List.of("ROLE_UNKNOWN")),
                404);
        call(
                "PUT",
                "/api/v1/admin/users/" + workerId + "/warehouse-scopes",
                admin,
                Map.of("scopes", List.of(Map.of("warehouseId", 99999999, "scopeRole", "OPERATOR"))),
                404);
        call("GET", "/api/v1/warehouses/" + north, worker, null, 200);
        call(
                "PUT",
                "/api/v1/admin/users/" + workerId + "/warehouse-scopes",
                admin,
                Map.of("scopes", List.of()),
                200);
        call("GET", "/api/v1/warehouses/" + north, worker, null, 403);
    }

    @Test
    void multibytePasswordLimitIsHandledWithoutInternalFailure() throws Exception {
        String password = "界".repeat(30);
        call(
                "POST",
                "/api/v1/admin/users",
                admin,
                Map.of(
                        "username",
                        "unicode-" + suffix,
                        "email",
                        "unicode-" + suffix + "@example.test",
                        "password",
                        password),
                400);
        call(
                "POST",
                "/api/v1/auth/login",
                null,
                Map.of("username", adminName, "password", password),
                401);
    }

    @Test
    void concurrentRoleReplacementDoesNotUnionPrivileges() throws Exception {
        long target =
                jdbc.queryForObject(
                        "insert into security_user(username,email,password_hash) values (?,?,?)"
                                + " returning id",
                        Long.class,
                        "replace-" + suffix,
                        "replace-" + suffix + "@example.test",
                        PASSWORD_HASH);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Integer>>();
            for (String role : List.of("ROLE_OPERATOR", "ROLE_AUDITOR")) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("Start timeout");
                                    }
                                    return mvc.perform(
                                                    org.springframework.test.web.servlet.request
                                                            .MockMvcRequestBuilders.put(
                                                                    "/api/v1/admin/users/"
                                                                            + target
                                                                            + "/roles")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + admin)
                                                            .contentType("application/json")
                                                            .content(
                                                                    mapper.writeValueAsString(
                                                                            Map.of(
                                                                                    "roles",
                                                                                    List.of(
                                                                                            role)))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(
                            List.of(
                                    futures.get(0).get(15, TimeUnit.SECONDS),
                                    futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactly(200, 200);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from security_user_role where user_id=?",
                                Integer.class,
                                target))
                .isEqualTo(1);
    }
}
