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
    void receiptIssueAndReadBalance() throws Exception {
        receipt(new BigDecimal("10.5"));
        call("POST", "/api/v1/movements/issue", worker, stock(north, new BigDecimal("3.25")), 201);
        assertThat(balance(north)).isEqualByComparingTo("7.25");
        var current =
                json(
                        call(
                                "GET",
                                "/api/v1/warehouses/" + north + "/inventory/" + product,
                                worker,
                                null,
                                200));
        assertThat(current.path("availableQuantity").decimalValue()).isEqualByComparingTo("7.25");
        assertThat(
                        json(call(
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
    void insufficientReservedAndInvalidQuantityAreRejected() throws Exception {
        receipt(BigDecimal.TEN);
        jdbc.update(
                "update inventory_balance set reserved_quantity=4 where product_id=? and"
                        + " warehouse_location_id=?",
                product,
                northLocation);
        call("POST", "/api/v1/movements/issue", admin, stock(north, new BigDecimal("7")), 409);
        call("POST", "/api/v1/movements/issue", admin, stock(north, BigDecimal.ZERO), 400);
        call("POST", "/api/v1/movements/receipt", admin, stock(north, new BigDecimal("-1")), 400);
        call(
                "POST",
                "/api/v1/movements/receipt",
                admin,
                stock(north, new BigDecimal("0.00001")),
                400);
        assertThat(balance(north)).isEqualByComparingTo("10");
    }

    @Test
    void signedAdjustmentAndReturnCreateLedgerEntries() throws Exception {
        receipt(BigDecimal.TEN);
        call(
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
        call(
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
        call(
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
        call("POST", "/api/v1/movements/return", worker, stock(north, BigDecimal.ONE), 201);
        assertThat(balance(north)).isEqualByComparingTo("10");
    }

    @Test
    void explicitLocationWorksWhenDefaultIsInactive() throws Exception {
        long other =
                jdbc.queryForObject(
                        "insert into warehouse_location(warehouse_id,code) values (?,'BIN-A')"
                                + " returning id",
                        Long.class,
                        north);
        jdbc.update("update warehouse_location set active=false where id=?", northLocation);
        var input = new HashMap<>(stock(north, BigDecimal.ONE));
        input.put("locationId", other);
        call("POST", "/api/v1/movements/receipt", admin, input, 201);
        assertThat(balance(north)).isEqualByComparingTo("1");
        input.put("locationId", southLocation);
        call("POST", "/api/v1/movements/receipt", admin, input, 404);
    }

    @Test
    void transferMovesStockInTwoLinkedAuditedLegs() throws Exception {
        receipt(BigDecimal.TEN);
        var result =
                json(call("POST", "/api/v1/transfers", admin, transfer(new BigDecimal("4")), 201));
        assertThat(balance(north)).isEqualByComparingTo("6");
        assertThat(balance(south)).isEqualByComparingTo("4");
        assertThat(result.path("movements").size()).isEqualTo(2);
        assertThat(result.path("movements").get(0).path("transferGroupId").asText())
                .isEqualTo(result.path("movements").get(1).path("transferGroupId").asText());
        call(
                "GET",
                "/api/v1/movements/" + result.path("movements").get(0).path("id").asText(),
                worker,
                null,
                403);
        var visible =
                json(call("GET", "/api/v1/movements?productId=" + product, worker, null, 200));
        assertThat(visible.path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void failedTransferRollsBackBothSidesAfterSourceWasUpdated() throws Exception {
        receipt(BigDecimal.TEN);
        BigDecimal max = new BigDecimal("999999999999999.9999");
        jdbc.update(
                "insert into inventory_balance(product_id,warehouse_location_id,quantity) values"
                        + " (?,?,?)",
                product,
                southLocation,
                max);
        int movements = count("stock_movement"),
                audits = count("stock_movement_audit"),
                events = count("audit_event");
        call("POST", "/api/v1/transfers", admin, transfer(BigDecimal.ONE), 409);
        assertThat(balance(north)).isEqualByComparingTo("10");
        assertThat(balance(south)).isEqualByComparingTo(max);
        assertThat(count("stock_movement")).isEqualTo(movements);
        assertThat(count("stock_movement_audit")).isEqualTo(audits);
        assertThat(count("audit_event")).isEqualTo(events);
    }

    @Test
    void transferInsufficientAndSameWarehouseFailWithoutChanges() throws Exception {
        receipt(BigDecimal.ONE);
        call("POST", "/api/v1/transfers", admin, transfer(BigDecimal.TEN), 409);
        var input = new HashMap<>(transfer(BigDecimal.ONE));
        input.put("destinationWarehouseId", north);
        call("POST", "/api/v1/transfers", admin, input, 400);
        assertThat(balance(north)).isEqualByComparingTo("1");
        assertThat(balance(south)).isZero();
    }

    @Test
    void concurrentIssuesCannotOversell() throws Exception {
        receipt(BigDecimal.TEN);
        var statuses =
                race(
                        "/api/v1/movements/issue",
                        stock(north, new BigDecimal("8")),
                        stock(north, new BigDecimal("8")));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(balance(north)).isEqualByComparingTo("2");
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
    void concurrentReceiptsCreateOneMissingBalanceAndPreserveBothAdds() throws Exception {
        assertThat(
                        race(
                                "/api/v1/movements/receipt",
                                stock(north, new BigDecimal("3")),
                                stock(north, new BigDecimal("7"))))
                .containsExactly(201, 201);
        assertThat(balance(north)).isEqualByComparingTo("10");
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
    void oppositeTransfersFinishWithoutDeadlock() throws Exception {
        receipt(BigDecimal.TEN);
        call("POST", "/api/v1/movements/receipt", admin, stock(south, BigDecimal.TEN), 201);
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
        assertThat(race("/api/v1/transfers", transfer(new BigDecimal("3")), reverse))
                .containsExactly(201, 201);
        assertThat(balance(north)).isEqualByComparingTo("10");
        assertThat(balance(south)).isEqualByComparingTo("10");
    }

    @Test
    void insertAndPostingUpdateAuditCaptureAuthenticatedActorAndSnapshots() throws Exception {
        String id = receipt(BigDecimal.ONE).path("id").asText();
        var audits =
                json(call("GET", "/api/v1/audit/movements/" + id, admin, null, 200))
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
            draft(connection, first);
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
            draft(connection, second);
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

    private void draft(Connection connection, UUID id) throws Exception {
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
        UUID id = UUID.fromString(receipt(BigDecimal.ONE).path("id").asText());
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
        assertThat(balance(north)).isEqualByComparingTo("1");
    }

    @Test
    void compensationPreservesOriginalAndCanOnlyBePostedOnce() throws Exception {
        var original = receipt(BigDecimal.TEN);
        String id = original.path("id").asText();
        var corrected =
                json(
                        call(
                                "POST",
                                "/api/v1/movements/" + id + "/compensate",
                                admin,
                                Map.of("reason", "Duplicate delivery"),
                                201));
        assertThat(balance(north)).isZero();
        assertThat(corrected.path("movements").get(0).path("compensatesMovementId").asText())
                .isEqualTo(id);
        var after = json(call("GET", "/api/v1/movements/" + id, admin, null, 200));
        assertThat(after).isEqualTo(original);
        call(
                "POST",
                "/api/v1/movements/" + id + "/compensate",
                admin,
                Map.of("reason", "Again"),
                409);
        call(
                "POST",
                "/api/v1/movements/"
                        + corrected.path("movements").get(0).path("id").asText()
                        + "/compensate",
                admin,
                Map.of("reason", "Again"),
                409);
        assertThat(balance(north)).isZero();
    }

    @Test
    void compensationOfTransferReversesBothLegsAtomically() throws Exception {
        receipt(BigDecimal.TEN);
        var transfer =
                json(call("POST", "/api/v1/transfers", admin, transfer(new BigDecimal("4")), 201));
        String out = transfer.path("movements").get(0).path("id").asText();
        var corrected =
                json(
                        call(
                                "POST",
                                "/api/v1/movements/" + out + "/compensate",
                                admin,
                                Map.of("reason", "Wrong warehouse"),
                                201));
        assertThat(corrected.path("movements").size()).isEqualTo(2);
        assertThat(balance(north)).isEqualByComparingTo("10");
        assertThat(balance(south)).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    List<Integer> race(String endpoint, Object first, Object second) throws Exception {
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
                                    return mvc.perform(
                                                    org.springframework.test.web.servlet.request
                                                            .MockMvcRequestBuilders.post(endpoint)
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + admin)
                                                            .contentType("application/json")
                                                            .content(
                                                                    mapper.writeValueAsString(
                                                                            body)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(
                    futures.get(0).get(15, TimeUnit.SECONDS),
                    futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }
}
