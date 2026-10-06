package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

class DatabaseIntegrityIntegrationTest extends PostgresIntegrationTest {
    @Autowired PlatformTransactionManager transactions;

    @Test
    void orphanTransferIsRejectedAtCommitAndAuditRollsBack() {
        UUID id = UUID.randomUUID(), group = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                status -> {
                                                    jdbc.update(
                                                            "insert into"
                                                                + " stock_movement(id,movement_number,movement_type,status,source_warehouse_id,target_warehouse_id,created_by,transfer_group_id)"
                                                                + " values"
                                                                + " (?,?,'TRANSFER_OUT','DRAFT',?,?,?,?)",
                                                            id,
                                                            "ORPHAN-" + id,
                                                            north,
                                                            south,
                                                            adminId,
                                                            group);
                                                }))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stock_movement where id=?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stock_movement_audit where movement_id=?",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void refreshParentCannotCrossSessionFamily() {
        UUID first =
                jdbc.queryForObject(
                        "insert into auth_session(user_id) values (?) returning id",
                        UUID.class,
                        adminId);
        UUID second =
                jdbc.queryForObject(
                        "insert into auth_session(user_id) values (?) returning id",
                        UUID.class,
                        adminId);
        UUID parent =
                jdbc.queryForObject(
                        "insert into auth_refresh_token(session_id,token_hash,expires_at) values"
                                + " (?,? ,now()+interval '1 day') returning id",
                        UUID.class,
                        first,
                        createRandomSixtyFourCharacterHexTokenHashFixture());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into"
                                            + " auth_refresh_token(session_id,parent_token_id,token_hash,expires_at)"
                                            + " values (?,?,?,now()+interval '1 day')",
                                        second,
                                        parent,
                                        createRandomSixtyFourCharacterHexTokenHashFixture()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private String createRandomSixtyFourCharacterHexTokenHashFixture() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void compensationWithMismatchedQuantityIsRejectedWithoutChangingOriginalStock() throws Exception {
        UUID original = UUID.fromString(postNorthWarehouseReceiptAndReturnMovement(BigDecimal.TEN).path("id").asText()),
                id = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                status -> {
                                                    jdbc.update(
                                                            "insert into"
                                                                + " stock_movement(id,movement_number,movement_type,status,source_warehouse_id,created_by,compensates_movement_id)"
                                                                + " values"
                                                                + " (?,?,'COMPENSATION','DRAFT',?,?,?)",
                                                            id,
                                                            "FORGED-" + id,
                                                            north,
                                                            adminId,
                                                            original);
                                                    jdbc.update(
                                                            "insert into"
                                                                + " stock_movement_line(movement_id,product_id,source_location_id,quantity)"
                                                                + " values (?,?,?,9)",
                                                            id,
                                                            product,
                                                            northLocation);
                                                    jdbc.update(
                                                            "update stock_movement set"
                                                                + " status='POSTED',posted_by=?,posted_at=now()"
                                                                + " where id=?",
                                                            adminId,
                                                            id);
                                                }))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from stock_movement where id=?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("10");
    }

    @Test
    void databaseRejectsDuplicateBalanceNegativeStockAndReservationsExceedingStock() throws Exception {
        postNorthWarehouseReceiptAndReturnMovement(BigDecimal.ONE);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into"
                                            + " inventory_balance(product_id,warehouse_location_id,quantity)"
                                            + " values (?,?,1)",
                                        product,
                                        northLocation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update inventory_balance set quantity=-1 where"
                                                + " product_id=?",
                                        product))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update inventory_balance set reserved_quantity=2 where"
                                                + " product_id=?",
                                        product))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(getFixtureProductTotalStockInWarehouse(north)).isEqualByComparingTo("1");
    }
}
