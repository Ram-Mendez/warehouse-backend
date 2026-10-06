package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

class InventoryMovementIntegrationTest extends PostgresIntegrationTest {
    @Test
    void receiptAndIssueUpdateTotalAndAvailableStockBalances() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(new BigDecimal("10.5"));
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/issue", worker, createStockMovementRequestBody(north, new BigDecimal("3.25")), 201);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("7.25");
        var current =
                parseHttpResponseJson(
                        executeHttpRequestAndAssertStatus(
                                "GET",
                                "/api/v1/warehouses/" + north + "/inventory/" + product,
                                worker,
                                null,
                                200));
        assertThat(current.path("availableQuantity").decimalValue()).isEqualByComparingTo("7.25");
        assertThat(
                        parseHttpResponseJson(executeHttpRequestAndAssertStatus(
                                        "GET",
                                        "/api/v1/inventory?productId=" + product,
                                        worker,
                                        null,
                                        200))
                                .path("content")
                                .size())
                .isEqualTo(1);
    }

    @Test
    void stockIssuesCannotConsumeReservedStockAndInvalidQuantitiesLeaveBalanceUnchanged() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN);
        jdbc.update(
                "update inventory_balance set reserved_quantity=4 where product_id=? and"
                        + " warehouse_location_id=?",
                product,
                northLocation);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/issue", admin, createStockMovementRequestBody(north, new BigDecimal("7")), 409);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/issue", admin, createStockMovementRequestBody(north, BigDecimal.ZERO), 400);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/receipt", admin, createStockMovementRequestBody(north, new BigDecimal("-1")), 400);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/movements/receipt",
                admin,
                createStockMovementRequestBody(north, new BigDecimal("0.00001")),
                400);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("10");
    }

    @Test
    void signedAdjustmentsAndStockReturnUpdateBalanceAndZeroAdjustmentIsRejected() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/movements/adjustment",
                manager,
                Map.of(
                        "warehouseId",
                        north,
                        "productId",
                        product,
                        "delta",
                        -3,
                        "reason",
                        "Count correction"),
                201);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/movements/adjustment",
                manager,
                Map.of(
                        "warehouseId",
                        north,
                        "productId",
                        product,
                        "delta",
                        2,
                        "reason",
                        "Count correction"),
                201);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/movements/adjustment",
                manager,
                Map.of(
                        "warehouseId",
                        north,
                        "productId",
                        product,
                        "delta",
                        0,
                        "reason",
                        "Count correction"),
                400);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/return", worker, createStockMovementRequestBody(north, BigDecimal.ONE), 201);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("10");
    }

    @Test
    void receiptAcceptsActiveExplicitLocationWhenDefaultIsInactiveAndRejectsForeignLocation() throws Exception {
        long other =
                jdbc.queryForObject(
                        "insert into warehouse_location(warehouse_id,code) values (?,'BIN-A')"
                                + " returning id",
                        Long.class,
                        north);
        jdbc.update("update warehouse_location set active=false where id=?", northLocation);
        var input = new HashMap<>(createStockMovementRequestBody(north, BigDecimal.ONE));
        input.put("locationId", other);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/receipt", admin, input, 201);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("1");
        input.put("locationId", southLocation);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/receipt", admin, input, 404);
    }

    @Test
    void transferUpdatesBothBalancesAndLinksMovementsHiddenFromPartiallyScopedUser() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN);
        var result =
                parseHttpResponseJson(executeHttpRequestAndAssertStatus("POST", "/api/v1/transfers", admin, createNorthToSouthTransferRequestBody(new BigDecimal("4")), 201));
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("6");
        assertThat(getFixtureProductTotalStockInWarehouse(south)).isEqualByComparingTo("4");
        assertThat(result.path("movements").size()).isEqualTo(2);
        assertThat(result.path("movements").get(0).path("transferGroupId").asText())
                .isEqualTo(result.path("movements").get(1).path("transferGroupId").asText());
        executeHttpRequestAndAssertStatus(
                "GET",
                "/api/v1/movements/" + result.path("movements").get(0).path("id").asText(),
                worker,
                null,
                403);
        var visible =
                parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/movements?productId=" + product, worker, null, 200));
        assertThat(visible.path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void failedTransferRollsBackBothSidesAfterSourceWasUpdated() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN);
        BigDecimal max = new BigDecimal("999999999999999.9999");
        jdbc.update(
                "insert into inventory_balance(product_id,warehouse_location_id,quantity) values"
                        + " (?,?,?)",
                product,
                southLocation,
                max);
        int movements = countRowsInTable("stock_movement"),
                audits = countRowsInTable("stock_movement_audit"),
                events = countRowsInTable("audit_event");
        executeHttpRequestAndAssertStatus("POST", "/api/v1/transfers", admin, createNorthToSouthTransferRequestBody(BigDecimal.ONE), 409);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("10");
        assertThat(getFixtureProductTotalStockInWarehouse(south)).isEqualByComparingTo(max);
        assertThat(countRowsInTable("stock_movement")).isEqualTo(movements);
        assertThat(countRowsInTable("stock_movement_audit")).isEqualTo(audits);
        assertThat(countRowsInTable("audit_event")).isEqualTo(events);
    }

    @Test
    void transferInsufficientAndSameWarehouseFailWithoutChanges() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.ONE);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/transfers", admin, createNorthToSouthTransferRequestBody(BigDecimal.TEN), 409);
        var input = new HashMap<>(createNorthToSouthTransferRequestBody(BigDecimal.ONE));
        input.put("destinationWarehouseId", north);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/transfers", admin, input, 400);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("1");
        assertThat(getFixtureProductTotalStockInWarehouse(south)).isZero();
    }

    @Test
    void concurrentStockIssuesCannotConsumeMoreThanAvailableStock() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN);
        var statuses =
                executeTwoPostRequestsConcurrentlyAndReturnStatuses(
                        "/api/v1/movements/issue",
                        createStockMovementRequestBody(north, new BigDecimal("8")),
                        createStockMovementRequestBody(north, new BigDecimal("8")));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("2");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stock_movement m join stock_movement_line l"
                                        + " on l.movement_id=m.id where l.product_id=? and"
                                        + " m.movement_type='ISSUE'",
                                Integer.class,
                                product))
                .isEqualTo(1);
    }

    @Test
    void concurrentReceiptsCreateOneMissingBalanceAndPreserveBothQuantityIncreases() throws Exception {
        assertThat(
                        executeTwoPostRequestsConcurrentlyAndReturnStatuses(
                                "/api/v1/movements/receipt",
                                createStockMovementRequestBody(north, new BigDecimal("3")),
                                createStockMovementRequestBody(north, new BigDecimal("7"))))
                .containsExactly(201, 201);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("10");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from inventory_balance where product_id=? and"
                                        + " warehouse_location_id=?",
                                Integer.class,
                                product,
                                northLocation))
                .isEqualTo(1);
    }

    @Test
    void concurrentOppositeTransfersBothSucceedAndPreserveWarehouseBalances() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN);
        executeHttpRequestAndAssertStatus("POST", "/api/v1/movements/receipt", admin, createStockMovementRequestBody(south, BigDecimal.TEN), 201);
        var reverse =
                Map.of(
                        "sourceWarehouseId",
                        south,
                        "destinationWarehouseId",
                        north,
                        "productId",
                        product,
                        "quantity",
                        3);
        // Queue the forward request first at the lower StockKey. A reversed lock order
        // lets the reverse request hold the higher row, producing a deadlock on release.
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> forward;
            Future<Integer> backward;
            try (var blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (var lock = blocker.prepareStatement(
                        "select id from inventory_balance where product_id=?"
                                + " and warehouse_location_id=? for update")) {
                    lock.setLong(1, product);
                    lock.setLong(2, Math.min(northLocation, southLocation));
                    try (var row = lock.executeQuery()) {
                        assertThat(row.next()).isTrue();
                    }
                }
                forward = pool.submit(() -> executePostRequestAndReturnStatus(
                        "/api/v1/transfers", createNorthToSouthTransferRequestBody(new BigDecimal("3"))));
                awaitBlockedDatabaseStatements("inventory_balance", 1);
                backward = pool.submit(() -> executePostRequestAndReturnStatus("/api/v1/transfers", reverse));
                awaitBlockedDatabaseStatements("inventory_balance", 2);
                blocker.rollback();
            }
            assertThat(List.of(forward.get(15, TimeUnit.SECONDS), backward.get(15, TimeUnit.SECONDS)))
                    .containsExactly(201, 201);
        }
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("10");
        assertThat(getFixtureProductTotalStockInWarehouse(south)).isEqualByComparingTo("10");
        var groups = jdbc.queryForList(
                "select m.transfer_group_id,m.source_warehouse_id,m.target_warehouse_id,"
                        + " count(*) legs, count(*) filter(where m.movement_type='TRANSFER_OUT') outbound,"
                        + " count(*) filter(where m.movement_type='TRANSFER_IN') inbound"
                        + " from stock_movement m join stock_movement_line l on l.movement_id=m.id"
                        + " where l.product_id=? and m.transfer_group_id is not null"
                        + " group by m.transfer_group_id,m.source_warehouse_id,m.target_warehouse_id",
                product);
        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(row -> row.get("source_warehouse_id"), row -> row.get("target_warehouse_id"))
                .containsExactlyInAnyOrder(tuple(north, south), tuple(south, north));
        for (var group : groups) {
            assertThat(group.get("legs")).isEqualTo(2L);
            assertThat(group.get("outbound")).isEqualTo(1L);
            assertThat(group.get("inbound")).isEqualTo(1L);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from stock_movement m join stock_movement_line l on l.movement_id=m.id"
                        + " join warehouse_location source on source.warehouse_id=m.source_warehouse_id"
                        + " join warehouse_location target on target.warehouse_id=m.target_warehouse_id"
                        + " where l.product_id=? and m.transfer_group_id is not null"
                        + " and m.status='POSTED' and m.created_by=? and m.posted_by=? and l.quantity=3"
                        + " and ((m.movement_type='TRANSFER_OUT' and l.source_location_id=source.id"
                        + " and l.target_location_id is null) or (m.movement_type='TRANSFER_IN'"
                        + " and l.source_location_id is null and l.target_location_id=target.id))",
                Integer.class, product, adminId, adminId)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "select count(*) from stock_movement_audit a join stock_movement m on m.id=a.movement_id"
                        + " join stock_movement_line l on l.movement_id=m.id"
                        + " where l.product_id=? and m.transfer_group_id is not null and a.actor_user_id=?",
                Integer.class, product, adminId)).isEqualTo(8);
    }

    @Test
    void insertAndPostingUpdateAuditCaptureAuthenticatedActorAndSnapshots() throws Exception {
        String id = postNorthWarehouseReceiptAndReturnMovement(BigDecimal.ONE).path("id").asText();
        var audits =
                parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/audit/movements/" + id, admin, null, 200))
                        .path("content");
        assertThat(audits.size()).isEqualTo(2);
        for (var row : audits) {
            assertThat(row.path("actorUserId").asLong()).isEqualTo(adminId);
        }
        assertThat(audits.get(0).path("operation").asText()).isEqualTo("UPDATE");
        assertThat(audits.get(0).path("oldRow").path("status").asText()).isEqualTo("DRAFT");
        assertThat(audits.get(0).path("newRow").path("status").asText()).isEqualTo("POSTED");
        assertThat(audits.get(1).path("oldRow").isNull()).isTrue();
    }

    @Autowired DataSource dataSource;

    @Test
    void actorIsTransactionLocalAndDraftDeleteHasOldSnapshot() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "select set_config('app.current_user_id',?,true)")) {
                statement.setString(1, Long.toString(adminId));
                statement.execute();
            }
            insertDraftReceiptMovementUsingConnection(connection, first);
            try (var statement =
                    connection.prepareStatement(
                            "update stock_movement set reason='Changed draft' where id=?")) {
                statement.setObject(1, first);
                statement.executeUpdate();
            }
            try (var statement =
                    connection.prepareStatement("delete from stock_movement where id=?")) {
                statement.setObject(1, first);
                statement.executeUpdate();
            }
            connection.commit();
            insertDraftReceiptMovementUsingConnection(connection, second);
            connection.commit();
            connection.setAutoCommit(true);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stock_movement_audit where movement_id=? and"
                                        + " actor_user_id=?",
                                Integer.class,
                                first,
                                adminId))
                .isEqualTo(3);
        var deleted =
                jdbc.queryForObject(
                        "select old_row::text from stock_movement_audit where movement_id=? and"
                                + " operation='DELETE'",
                        String.class,
                        first);
        assertThat(mapper.readTree(deleted).path("reason").asText()).isEqualTo("Changed draft");
        assertThat(
                        jdbc.queryForObject(
                                "select actor_user_id is null from stock_movement_audit where"
                                        + " movement_id=?",
                                Boolean.class,
                                second))
                .isTrue();
    }

    private void insertDraftReceiptMovementUsingConnection(Connection connection, UUID id) throws Exception {
        try (var statement =
                connection.prepareStatement(
                        "insert into"
                            + " stock_movement(id,movement_number,movement_type,status,target_warehouse_id,created_by)"
                            + " values (?,?,'RECEIPT','DRAFT',?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, "TEST-" + id);
            statement.setLong(3, north);
            statement.setLong(4, adminId);
            statement.executeUpdate();
        }
    }

    @Test
    void postedHeadersAndLinesRejectSilentMutationAtDatabase() throws Exception {
        UUID id = UUID.fromString(postNorthWarehouseReceiptAndReturnMovement(BigDecimal.ONE).path("id").asText());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update stock_movement set reason='rewrite' where id=?",
                                        id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from stock_movement where id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update stock_movement_line set quantity=2 where"
                                                + " movement_id=?",
                                        id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "delete from stock_movement_line where movement_id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into"
                                            + " stock_movement_line(movement_id,product_id,target_location_id,quantity)"
                                            + " values (?,?,?,1)",
                                        id,
                                        product,
                                        northLocation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("1");
    }

    @Test
    void compensationPreservesOriginalAndCanOnlyBePostedOnce() throws Exception {
        var original = postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN);
        String id = original.path("id").asText();
        var corrected =
                parseHttpResponseJson(
                        executeHttpRequestAndAssertStatus(
                                "POST",
                                "/api/v1/movements/" + id + "/compensate",
                                admin,
                                Map.of("reason", "Duplicate delivery"),
                                201));
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isZero();
        assertThat(corrected.path("movements").get(0).path("compensatesMovementId").asText())
                .isEqualTo(id);
        var after = parseHttpResponseJson(executeHttpRequestAndAssertStatus("GET", "/api/v1/movements/" + id, admin, null, 200));
        assertThat(after).isEqualTo(original);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/movements/" + id + "/compensate",
                admin,
                Map.of("reason", "Again"),
                409);
        executeHttpRequestAndAssertStatus(
                "POST",
                "/api/v1/movements/"
                        + corrected.path("movements").get(0).path("id").asText()
                        + "/compensate",
                admin,
                Map.of("reason", "Again"),
                409);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isZero();
    }

    @Test
    void compensatingTransferCreatesTwoReversingMovementsAndRestoresBothBalances() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN);
        var transfer =
                parseHttpResponseJson(executeHttpRequestAndAssertStatus("POST", "/api/v1/transfers", admin, createNorthToSouthTransferRequestBody(new BigDecimal("4")), 201));
        String out = transfer.path("movements").get(0).path("id").asText();
        var corrected =
                parseHttpResponseJson(
                        executeHttpRequestAndAssertStatus(
                                "POST",
                                "/api/v1/movements/" + out + "/compensate",
                                admin,
                                Map.of("reason", "Wrong warehouse"),
                                201));
        assertThat(corrected.path("movements").size()).isEqualTo(2);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("10");
        assertThat(getFixtureProductTotalStockInWarehouse(south)).isZero();
    }

    private int countRowsInTable(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    List<Integer> executeTwoPostRequestsConcurrentlyAndReturnStatuses(String endpoint, Object first, Object second) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Integer>>();
            for (Object body : List.of(first, second)) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!go.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("Race start timeout");
                                    }
                                    return executePostRequestAndReturnStatus(endpoint, body);
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(
                    futures.get(0).get(15, TimeUnit.SECONDS),
                    futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    private int executePostRequestAndReturnStatus(String endpoint, Object body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint)
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }
}
