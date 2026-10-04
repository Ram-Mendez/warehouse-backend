package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Map;

class CatalogSecurityIntegrationTest extends PostgresIntegrationTest {
    @Test
    void auditUpdateCannotRevealAnUnscopedHistoricalWarehouse() throws Exception {
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbc.update(
                "insert into"
                    + " stock_movement(id,movement_number,movement_type,status,target_warehouse_id,created_by)"
                    + " values (?,?,'RECEIPT','DRAFT',?,?)",
                id,
                "DRAFT-" + id,
                south,
                adminId);
        jdbc.update("update stock_movement set target_warehouse_id=? where id=?", north, id);
        jdbc.update(
                "delete from security_user_warehouse_scope where user_id=? and warehouse_id=?",
                managerId,
                south);
        var hidden = json(call("GET", "/api/v1/audit/movements/" + id, manager, null, 200));
        assertThat(hidden.path("totalElements").asInt()).isZero();
        var visible = json(call("GET", "/api/v1/audit/movements/" + id, admin, null, 200));
        assertThat(visible.path("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void categoryCrudAndInactiveState() throws Exception {
        var input =
                Map.of(
                        "code",
                        "CAT-" + suffix,
                        "name",
                        "New category",
                        "parentId",
                        category,
                        "active",
                        true);
        var created = json(call("POST", "/api/v1/categories", admin, input, 201));
        long id = created.path("id").asLong();
        call("GET", "/api/v1/categories/" + id, worker, null, 200);
        call(
                "PUT",
                "/api/v1/categories/" + id,
                admin,
                Map.of("code", "CAT-" + suffix, "name", "Renamed", "active", false),
                200);
        call("POST", "/api/v1/categories", admin, input, 409);
        call("POST", "/api/v1/categories", worker, input, 403);
        call(
                "POST",
                "/api/v1/categories",
                admin,
                Map.of("code", " ", "name", " ", "active", true),
                400);
        call("GET", "/api/v1/categories/99999999", admin, null, 404);
    }

    @Test
    void categoryCannotBecomeItsOwnAncestor() throws Exception {
        call(
                "PUT",
                "/api/v1/categories/" + category,
                admin,
                Map.of(
                        "code",
                        "C-" + suffix,
                        "name",
                        "Cycle",
                        "parentId",
                        category,
                        "active",
                        true),
                409);
    }

    @Test
    void supplierCrudAndValidation() throws Exception {
        var input =
                Map.of(
                        "code",
                        "SUP-" + suffix,
                        "name",
                        "Supplier",
                        "email",
                        "supplier@example.test",
                        "active",
                        true);
        long id = json(call("POST", "/api/v1/suppliers", admin, input, 201)).path("id").asLong();
        call("GET", "/api/v1/suppliers/" + id, worker, null, 200);
        call(
                "PUT",
                "/api/v1/suppliers/" + id,
                admin,
                Map.of("code", "SUP-" + suffix, "name", "Inactive supplier", "active", false),
                200);
        call("POST", "/api/v1/suppliers", admin, input, 409);
        call(
                "POST",
                "/api/v1/suppliers",
                admin,
                Map.of("code", "BAD-" + suffix, "name", "Bad", "email", "broken", "active", true),
                400);
    }

    @Test
    void warehouseCreationGrantsOnlyCreatorAndCreatesDefaultLocation() throws Exception {
        var created =
                json(
                        call(
                                "POST",
                                "/api/v1/warehouses",
                                manager,
                                Map.of(
                                        "code",
                                        "NEW-" + suffix,
                                        "name",
                                        "New warehouse",
                                        "active",
                                        true),
                                201));
        long id = created.path("id").asLong();
        call("GET", "/api/v1/warehouses/" + id, manager, null, 200);
        call("GET", "/api/v1/warehouses/" + id, admin, null, 403);
        call(
                "GET",
                "/api/v1/warehouses/" + north + "/locations/" + northLocation,
                worker,
                null,
                200);
        call(
                "GET",
                "/api/v1/warehouses/" + south + "/locations/" + southLocation,
                worker,
                null,
                403);
        call(
                "PUT",
                "/api/v1/warehouses/" + id,
                manager,
                Map.of("code", "NEW-" + suffix, "name", "Updated warehouse", "active", true),
                200);
        assertThat(
                        json(call(
                                        "GET",
                                        "/api/v1/warehouses/" + id + "/locations",
                                        manager,
                                        null,
                                        200))
                                .path("content")
                                .get(0)
                                .path("code")
                                .asText())
                .isEqualTo("DEFAULT");
    }

    @Test
    void warehouseScopeAndPermissionsAreBothRequired() throws Exception {
        call("GET", "/api/v1/warehouses/" + north, worker, null, 200);
        call("GET", "/api/v1/warehouses/" + south, worker, null, 403);
        call("GET", "/api/v1/warehouses/" + south + "/inventory", worker, null, 403);
        var list = json(call("GET", "/api/v1/warehouses", worker, null, 200));
        assertThat(list.path("totalElements").asLong()).isEqualTo(1);
        call("POST", "/api/v1/transfers", worker, transfer(java.math.BigDecimal.ONE), 403);
        jdbc.update("delete from security_user_role where user_id=?", workerId);
        call("GET", "/api/v1/warehouses/" + north, worker, null, 403);
    }

    @Test
    void viewerScopeCannotMutateStockDespiteGlobalPermission() throws Exception {
        jdbc.update(
                "update security_user_warehouse_scope set scope_role='VIEWER' where user_id=?",
                workerId);
        call("GET", "/api/v1/warehouses/" + north + "/inventory", worker, null, 200);
        call(
                "POST",
                "/api/v1/movements/receipt",
                worker,
                stock(north, java.math.BigDecimal.ONE),
                403);
    }

    @Test
    void productCreateDuplicateSkuReadAndVersionedUpdate() throws Exception {
        var input = productInput("SKU-" + suffix, "New product");
        var created = json(call("POST", "/api/v1/products", admin, input, 201));
        long id = created.path("id").asLong();
        assertThat(created.path("version").asLong()).isZero();
        call("GET", "/api/v1/products/" + id, worker, null, 200);
        var links = json(call("GET", "/api/v1/products/" + id + "/suppliers", worker, null, 200));
        assertThat(links.path("content").get(0).path("supplierId").asLong()).isEqualTo(supplier);
        call("POST", "/api/v1/products", admin, input, 409);
        call("POST", "/api/v1/products", worker, productInput("OTHER-" + suffix, "Denied"), 403);
        var changed = new java.util.HashMap<>(input);
        changed.put("name", "Changed");
        changed.put("version", 0);
        var updated = json(call("PUT", "/api/v1/products/" + id, admin, changed, 200));
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        call("PUT", "/api/v1/products/" + id, admin, changed, 409);
        assertThat(
                        jdbc.queryForObject(
                                "select unit_cost from product_supplier where product_id=? and"
                                        + " supplier_id=?",
                                java.math.BigDecimal.class,
                                id,
                                supplier))
                .isEqualByComparingTo("2.5");
    }

    @Test
    void productFilteringPaginationAndSorting() throws Exception {
        call(
                "POST",
                "/api/v1/products",
                admin,
                productInput("FILTER-" + suffix, "Findable product"),
                201);
        var found =
                json(
                        call(
                                "GET",
                                "/api/v1/products?search=Findable&categoryId="
                                        + category
                                        + "&supplierId="
                                        + supplier
                                        + "&active=true&sort=sku&size=1",
                                worker,
                                null,
                                200));
        assertThat(found.path("content").size()).isEqualTo(1);
        assertThat(found.path("totalElements").asInt()).isEqualTo(1);
        call("GET", "/api/v1/products?size=101", admin, null, 400);
        call("GET", "/api/v1/products?sort=sql", admin, null, 400);
        call("GET", "/api/v1/products?sku=FILTER-" + suffix, admin, null, 200);
    }

    @Test
    void supplierOnlyProductEditAdvancesVersionAndRejectsStaleUpdate() throws Exception {
        var input = productInput("COST-" + suffix, "Costed product");
        var created = json(call("POST", "/api/v1/products", admin, input, 201));
        long id = created.path("id").asLong();
        var update = new java.util.HashMap<>(input);
        update.put("version", 0);
        update.put("unitCost", 8.25);
        var changed = json(call("PUT", "/api/v1/products/" + id, admin, update, 200));
        assertThat(changed.path("version").asLong()).isEqualTo(1);
        update.put("unitCost", 9.75);
        call("PUT", "/api/v1/products/" + id, admin, update, 409);
        assertThat(
                        jdbc.queryForObject(
                                "select unit_cost from product_supplier where product_id=? and"
                                        + " supplier_id=?",
                                java.math.BigDecimal.class,
                                id,
                                supplier))
                .isEqualByComparingTo("8.25");
    }

    @Test
    void concurrentCategoryParentChangesCannotCreateCycle() throws Exception {
        long other =
                jdbc.queryForObject(
                        "insert into category(code,name) values (?,'Other category') returning id",
                        Long.class,
                        "OTHER-" + suffix);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (long id : java.util.List.of(category, other)) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("Start timeout");
                                    }
                                    return mvc.perform(
                                                    org.springframework.test.web.servlet.request
                                                            .MockMvcRequestBuilders.put(
                                                                    "/api/v1/categories/" + id)
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + admin)
                                                            .contentType("application/json")
                                                            .content(
                                                                    mapper.writeValueAsString(
                                                                            Map.of(
                                                                                    "code",
                                                                                    id == category
                                                                                            ? "C-"
                                                                                                    + suffix
                                                                                            : "OTHER-"
                                                                                                    + suffix,
                                                                                    "name",
                                                                                    "Updated",
                                                                                    "parentId",
                                                                                    id == category
                                                                                            ? other
                                                                                            : category,
                                                                                    "active",
                                                                                    true))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(
                            java.util.List.of(
                                    futures.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS),
                                    futures.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void productSearchEscapesUnderscorePercentAndBackslashLiterally() throws Exception {
        String prefix = "LITERAL" + suffix;
        call("POST", "/api/v1/products", admin, productInput(prefix + "_A", "Underscore"), 201);
        call("POST", "/api/v1/products", admin, productInput(prefix + "XA", "Decoy"), 201);
        call("POST", "/api/v1/products", admin, productInput(prefix + "%B", "Percent"), 201);
        call("POST", "/api/v1/products", admin, productInput(prefix + "XB", "Decoy percent"), 201);
        call("POST", "/api/v1/products", admin, productInput(prefix + "\\C", "Backslash"), 201);
        for (String literal : java.util.List.of("_", "%", "\\")) {
            String search =
                    java.net.URLEncoder.encode(
                            prefix + literal, java.nio.charset.StandardCharsets.UTF_8);
            var result = json(call("GET", "/api/v1/products?search=" + search, admin, null, 200));
            assertThat(result.path("totalElements").asInt()).isEqualTo(1);
        }
    }

    @Test
    void auditReadsRespectBothWarehouseScopes() throws Exception {
        var movement =
                json(
                        call(
                                "POST",
                                "/api/v1/movements/receipt",
                                admin,
                                stock(south, java.math.BigDecimal.ONE),
                                201));
        jdbc.update(
                "delete from security_user_warehouse_scope where user_id=? and warehouse_id=?",
                managerId,
                south);
        call("GET", "/api/v1/audit/movements/" + movement.path("id").asText(), manager, null, 403);
        var events =
                json(
                        call(
                                "GET",
                                "/api/v1/audit/events?entityId=" + movement.path("id").asText(),
                                manager,
                                null,
                                200));
        assertThat(events.path("totalElements").asInt()).isZero();
        var list = json(call("GET", "/api/v1/audit/stock-movements", manager, null, 200));
        for (var row : list.path("content")) {
            assertThat(row.path("newRow").path("target_warehouse_id").asLong()).isNotEqualTo(south);
        }
        call("GET", "/api/v1/audit/events", worker, null, 403);
    }

    @Test
    void correlationAndFrameworkErrorsAreConsistent() throws Exception {
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/api/v1/products")
                                        .header("X-Correlation-Id", "integration-request"))
                        .andReturn();
        var error = json(response);
        assertThat(error.path("status").asInt()).isEqualTo(401);
        assertThat(error.path("correlationId").asText()).isEqualTo("integration-request");
        assertThat(response.getResponse().getHeader("X-Correlation-Id"))
                .isEqualTo("integration-request");
        call("GET", "/api/v1/not-an-endpoint", admin, null, 404);
        call("DELETE", "/api/v1/products/" + product, admin, null, 405);
    }
}
