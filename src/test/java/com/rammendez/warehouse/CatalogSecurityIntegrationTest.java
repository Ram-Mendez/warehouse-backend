package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Map;

class CatalogSecurityIntegrationTest extends PostgresIntegrationTest {
    @Test
    void movementAuditHidesUpdateHistoryWhenUserLacksPreviousWarehouseScope() throws Exception {
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
        var hidden = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/audit/movements/" + id, manager, null, 200));
        assertThat(hidden.path("totalElements").asInt()).isZero();
        var visible = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/audit/movements/" + id, admin, null, 200));
        assertThat(visible.path("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void categoryCreationReadAndDeactivationEnforceUniquenessPermissionsAndValidation() throws Exception {
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
        var created = parseHttpResponseJson(executeHttpRequestAndAssertStatus("POST", "/api/v1/categories", admin, input, 201));
        long id = created.path("id").asLong();
        executeHttpRequestAndAssertStatus("GET", "/api/v1/categories/" + id, worker, null, 200);
        executeHttpRequestAndAssertStatus(
                "PUT",
                "/api/v1/categories/" + id,
                admin,
                Map.of("code", "CAT-" + suffix, "name", "Renamed", "active", false),
                200);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/categories", admin, input, 409);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/categories", worker, input, 403);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/categories",
                admin,
                Map.of("code", " ", "name", " ", "active", true),
                400);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/categories/99999999", admin, null, 404);
    }

    @Test
    void categoryCannotSetItselfAsParent() throws Exception {
        executeHttpRequestAndAssertStatus(
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
    void supplierCreationReadAndDeactivationRejectDuplicateCodesAndInvalidEmail() throws Exception {
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
        long id = parseHttpResponseJson(executeHttpRequestAndAssertStatus("POST", "/api/v1/suppliers", admin, input, 201)).path("id").asLong();
        executeHttpRequestAndAssertStatus("GET", "/api/v1/suppliers/" + id, worker, null, 200);
        executeHttpRequestAndAssertStatus(
                "PUT",
                "/api/v1/suppliers/" + id,
                admin,
                Map.of("code", "SUP-" + suffix, "name", "Inactive supplier", "active", false),
                200);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/suppliers", admin, input, 409);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/suppliers",
                admin,
                Map.of("code", "BAD-" + suffix, "name", "Bad", "email", "broken", "active", true),
                400);
    }

    @Test
    void warehouseCreationGrantsOnlyCreatorAndCreatesDefaultLocation() throws Exception {
        var created =
                parseHttpResponseJson(
                        executeHttpRequestAndAssertStatus(
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
        executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses/" + id, manager, null, 200);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses/" + id, admin, null, 403);
        executeHttpRequestAndAssertStatus(
                "GET",
                "/api/v1/warehouses/" + north + "/locations/" + northLocation,
                worker,
                null,
                200);
        executeHttpRequestAndAssertStatus(
                "GET",
                "/api/v1/warehouses/" + south + "/locations/" + southLocation,
                worker,
                null,
                403);
        executeHttpRequestAndAssertStatus(
                "PUT",
                "/api/v1/warehouses/" + id,
                manager,
                Map.of("code", "NEW-" + suffix, "name", "Updated warehouse", "active", true),
                200);
        assertThat(
                        parseHttpResponseJson(executeHttpRequestAndAssertStatus(
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
        executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses/" + north, worker, null, 200);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses/" + south, worker, null, 403);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses/" + south + "/inventory", worker, null, 403);
        var list = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses", worker, null, 200));
        assertThat(list.path("totalElements").asLong()).isEqualTo(1);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/transfers", worker, createNorthToSouthTransferRequestBody(java.math.BigDecimal.ONE), 403);
        postNorthWarehouseReceiptAndReturnMovement(java.math.BigDecimal.TEN);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/receipt", admin,
                createStockMovementRequestBody(south, java.math.BigDecimal.TEN), 201);
        for (String filter : java.util.List.of("", "?productId=" + product,
                "?warehouseId=" + north, "?warehouseId=" + north + "&productId=" + product)) {
            var inventory = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                    "GET", "/api/v1/inventory" + filter, worker, null, 200));
            assertThat(inventory.path("totalElements").asLong()).isEqualTo(1);
            assertThat(inventory.path("content").size()).isEqualTo(1);
            assertThat(inventory.path("content").get(0).path("warehouseId").asLong()).isEqualTo(north);
            assertThat(inventory.path("content").get(0).path("productId").asLong()).isEqualTo(product);
        }
        executeHttpRequestAndAssertStatus("GET", "/api/v1/inventory?warehouseId=" + south, worker, null, 403);
        jdbc.update("delete from security_user_warehouse_scope where user_id=?", workerId);
        var empty = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                "GET", "/api/v1/inventory", worker, null, 200));
        assertThat(empty.path("totalElements").asLong()).isZero();
        assertThat(empty.path("content").size()).isZero();
        jdbc.update("delete from security_user_role where user_id=?", workerId);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses/" + north, worker, null, 403);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/inventory", worker, null, 403);
    }

    @Test
    void viewerScopeCannotMutateStockDespiteGlobalPermission() throws Exception {
        jdbc.update(
                "update security_user_warehouse_scope set scope_role='VIEWER' where user_id=?",
                workerId);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/warehouses/" + north + "/inventory", worker, null, 200);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/movements/receipt",
                worker,
                createStockMovementRequestBody(north, java.math.BigDecimal.ONE),
                403);
    }

    @Test
    void productCreationLinksSupplierAndRejectsDuplicateSkuWhileUpdatesRejectStaleVersion() throws Exception {
        var input = createProductWithSupplierRequestBody("SKU-" + suffix, "New product");
        var created = parseHttpResponseJson(executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, input, 201));
        long id = created.path("id").asLong();
        assertThat(created.path("version").asLong()).isZero();
        executeHttpRequestAndAssertStatus("GET", "/api/v1/products/" + id, worker, null, 200);
        var links = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/products/" + id + "/suppliers", worker, null, 200));
        assertThat(links.path("content").get(0).path("supplierId").asLong()).isEqualTo(supplier);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, input, 409);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/products", worker, createProductWithSupplierRequestBody("OTHER-" + suffix, "Denied"), 403);
        var changed = new java.util.HashMap<>(input);
        changed.put("name", "Changed");
        changed.put("version", 0);
        var updated = parseHttpResponseJson(executeHttpRequestAndAssertStatus("PUT", "/api/v1/products/" + id, admin, changed, 200));
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        var rejected = new java.util.HashMap<>(changed);
        rejected.put("name", "Rejected overwrite");
        rejected.put("unitCost", 99);
        for (Long version : new Long[] {0L, 99L, null}) {
            if (version == null) {
                rejected.remove("version");
            } else {
                rejected.put("version", version);
            }
            executeHttpRequestAndAssertStatus("PUT", "/api/v1/products/" + id, admin, rejected, 409);
            var preserved = parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                    "GET", "/api/v1/products/" + id, admin, null, 200));
            assertThat(preserved.path("name").asText()).isEqualTo("Changed");
            assertThat(preserved.path("version").asLong()).isEqualTo(1);
        }
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
    void productFiltersReturnMatchingProductAndRejectExcessivePageSizeOrUnknownSort() throws Exception {
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/products",
                admin,
                createProductWithSupplierRequestBody("FILTER-" + suffix, "Findable product"),
                201);
        var found =
                parseHttpResponseJson(
                        executeHttpRequestAndAssertStatus(
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
        executeHttpRequestAndAssertStatus("GET", "/api/v1/products?size=101", admin, null, 400);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/products?sort=sql", admin, null, 400);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/products?sku=FILTER-" + suffix, admin, null, 200);
    }

    @Test
    void supplierOnlyProductEditAdvancesVersionAndRejectsStaleUpdate() throws Exception {
        var input = createProductWithSupplierRequestBody("COST-" + suffix, "Costed product");
        var created = parseHttpResponseJson(executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, input, 201));
        long id = created.path("id").asLong();
        var update = new java.util.HashMap<>(input);
        update.put("version", 0);
        update.put("unitCost", 8.25);
        var changed = parseHttpResponseJson(executeHttpRequestAndAssertStatus("PUT", "/api/v1/products/" + id, admin, update, 200));
        assertThat(changed.path("version").asLong()).isEqualTo(1);
        update.put("unitCost", 9.75);
        executeHttpRequestAndAssertStatus("PUT", "/api/v1/products/" + id, admin, update, 409);
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
        executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, createProductWithSupplierRequestBody(prefix + "_A", "Underscore"), 201);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, createProductWithSupplierRequestBody(prefix + "XA", "Decoy"), 201);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, createProductWithSupplierRequestBody(prefix + "%B", "Percent"), 201);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, createProductWithSupplierRequestBody(prefix + "XB", "Decoy percent"), 201);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/products", admin, createProductWithSupplierRequestBody(prefix + "\\C", "Backslash"), 201);
        for (String literal : java.util.List.of("_", "%", "\\")) {
            String search =
                    java.net.URLEncoder.encode(
                            prefix + literal, java.nio.charset.StandardCharsets.UTF_8);
            var result = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/products?search=" + search, admin, null, 200));
            assertThat(result.path("totalElements").asInt()).isEqualTo(1);
            assertThat(result.path("content").size()).isEqualTo(1);
            assertThat(result.path("content").get(0).path("sku").asText()).startsWith(prefix + literal);
        }
    }

    @Test
    void auditQueriesExcludeUnscopedWarehouseHistoryAndRejectUnauthorizedReads() throws Exception {
        var movement =
                parseHttpResponseJson(
                        executeHttpRequestAndAssertStatus(
                                "POST",
                                "/api/v1/movements/receipt",
                                admin,
                                createStockMovementRequestBody(south, java.math.BigDecimal.ONE),
                                201));
        jdbc.update(
                "delete from security_user_warehouse_scope where user_id=? and warehouse_id=?",
                managerId,
                south);
        executeHttpRequestAndAssertStatus("GET", "/api/v1/audit/movements/" + movement.path("id").asText(), manager, null, 403);
        var events =
                parseHttpResponseJson(
                        executeHttpRequestAndAssertStatus(
                                "GET",
                                "/api/v1/audit/events?entityId=" + movement.path("id").asText(),
                                manager,
                                null,
                                200));
        assertThat(events.path("totalElements").asInt()).isZero();
        var list = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/audit/stock-movements", manager, null, 200));
        for (var row : list.path("content")) {
            assertThat(row.path("newRow").path("target_warehouse_id").asLong()).isNotEqualTo(south);
        }
        executeHttpRequestAndAssertStatus("GET", "/api/v1/audit/events", worker, null, 403);
    }

    @Test
    void unauthorizedResponsePreservesCorrelationIdAndUnknownOrUnsupportedEndpointsReturnExpectedStatuses() throws Exception {
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/api/v1/products")
                                        .header("X-Correlation-Id", "integration-request"))
                        .andReturn();
        var error = parseHttpResponseJson(response);
        assertThat(error.path("status").asInt()).isEqualTo(401);
        assertThat(error.path("correlationId").asText()).isEqualTo("integration-request");
        assertThat(response.getResponse().getHeader("X-Correlation-Id"))
                .isEqualTo("integration-request");
        executeHttpRequestAndAssertStatus("GET", "/api/v1/not-an-endpoint", admin, null, 404);
        executeHttpRequestAndAssertStatus("DELETE", "/api/v1/products/" + product, admin, null, 405);
    }
}
