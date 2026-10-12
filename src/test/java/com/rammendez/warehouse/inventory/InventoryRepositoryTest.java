package com.rammendez.warehouse.inventory;

import com.rammendez.warehouse.common.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class InventoryRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private InventoryRepository repository;

    @Autowired
    private InventoryRepository queries;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createInventoryBalanceIfMissing() {
        long warehouseId = warehouse();
        long locationId = location(warehouseId, "A");
        long productId = product("10", true);

        repository.createInventoryBalanceIfMissing(productId, locationId);
        repository.createInventoryBalanceIfMissing(productId, locationId);

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_balance
                WHERE product_id = ? AND warehouse_location_id = ?
                """, Long.class, productId, locationId);
        assertEquals(1L, count);
        assertDecimal("0", jdbc.queryForObject("""
                SELECT quantity FROM inventory_balance
                WHERE product_id = ? AND warehouse_location_id = ?
                """, BigDecimal.class, productId, locationId));
    }

    @Test
    void lockInventoryBalanceAndGetAvailableQuantity() {
        long warehouseId = warehouse();
        long locationId = location(warehouseId, "A");
        long productId = product("10", true);
        balance(productId, locationId, "12", "5");

        BigDecimal available = repository.lockInventoryBalanceAndGetAvailableQuantity(productId, locationId);

        assertDecimal("7", available);
    }

    @Test
    void applyQuantityDeltaAndAdvanceBalanceVersion() {
        long warehouseId = warehouse();
        long locationId = location(warehouseId, "A");
        long productId = product("10", true);
        balance(productId, locationId, "12", "5");

        repository.applyQuantityDeltaAndAdvanceBalanceVersion(productId, locationId, new BigDecimal("-3"));

        var row = jdbc.queryForMap("""
                SELECT quantity, reserved_quantity, version
                FROM inventory_balance
                WHERE product_id = ? AND warehouse_location_id = ?
                """, productId, locationId);
        assertDecimal("9", (BigDecimal) row.get("quantity"));
        assertDecimal("5", (BigDecimal) row.get("reserved_quantity"));
        assertEquals(1L, ((Number) row.get("version")).longValue());

        // Even a zero delta is a recorded balance update under the existing repository contract.
        repository.applyQuantityDeltaAndAdvanceBalanceVersion(productId, locationId, BigDecimal.ZERO);
        assertEquals(2L, jdbc.queryForObject("""
                SELECT version FROM inventory_balance
                WHERE product_id = ? AND warehouse_location_id = ?
                """, Long.class, productId, locationId));
        assertDecimal("4", repository.lockInventoryBalanceAndGetAvailableQuantity(productId, locationId));

    }

    @Test
    void sumProductStockAcrossWarehouseLocations() {
        long warehouseId = warehouse();
        long otherWarehouseId = warehouse();
        long firstLocation = location(warehouseId, "A");
        long secondLocation = location(warehouseId, "B");
        long otherLocation = location(otherWarehouseId, "A");
        long productId = product("10", true);
        balance(productId, firstLocation, "12", "5");
        balance(productId, secondLocation, "8", "2");
        balance(productId, otherLocation, "100", "0");

        InventoryDtos.Stock result = queries.sumProductStockAcrossWarehouseLocations(warehouseId, productId);

        assertEquals(warehouseId, result.warehouseId());
        assertEquals(productId, result.productId());
        assertDecimal("20", result.quantity());
        assertDecimal("7", result.reservedQuantity());
        assertDecimal("13", result.availableQuantity());
    }

    @Test
    void listInventoryBalancesWithinUserScope() {
        long allowedWarehouse = warehouse();
        long deniedWarehouse = warehouse();
        long allowedLocation = location(allowedWarehouse, "A");
        long deniedLocation = location(deniedWarehouse, "A");
        long allowedProduct = product("10", true);
        long deniedProduct = product("10", true);
        long userId = user();

        balance(allowedProduct, allowedLocation, "12", "5");
        balance(deniedProduct, deniedLocation, "50", "0");
        jdbc.update("""
                INSERT INTO security_user_warehouse_scope(user_id, warehouse_id, scope_role)
                VALUES (?, ?, 'VIEWER')
                """, userId, allowedWarehouse);

        PageResponse<InventoryDtos.Balance> page = queries.listInventoryBalancesWithinUserScope(userId, null, null, 0, 20);

        assertEquals(1L, totalElements(page));
        List<InventoryDtos.Balance> rows = content(page);
        assertEquals(1, rows.size());
        assertEquals(allowedWarehouse, rows.get(0).warehouseId());
        assertEquals(allowedProduct, rows.get(0).productId());
        assertDecimal("7", rows.get(0).availableQuantity());

        PageResponse<InventoryDtos.Balance> outsideScope = queries.listInventoryBalancesWithinUserScope(userId, deniedWarehouse, null, 0, 20);
        assertEquals(0L, totalElements(outsideScope));
        assertTrue(content(outsideScope).isEmpty());
    }

    @Test
    void getProductsShortage() {
        long warehouseId = warehouse();
        long otherWarehouseId = warehouse();
        long firstLocation = location(warehouseId, "A");
        long secondLocation = location(warehouseId, "B");
        long otherLocation = location(otherWarehouseId, "A");

        long reservedProduct = product("10", true); // 12 - 5 = 7; faltan 3
        long noBalanceProduct = product("8", true); // sin balance; faltan 8
        long enoughProduct = product("10", true); // 10 disponibles; no aparece
        long inactiveProduct = product("20", false); // inactivo; no aparece

        balance(reservedProduct, firstLocation, "9", "4");
        balance(reservedProduct, secondLocation, "3", "1");
        balance(reservedProduct, otherLocation, "50", "0"); // otro almacén: no sumar
        balance(enoughProduct, firstLocation, "10", "0");
        balance(inactiveProduct, firstLocation, "1", "0");

        PageResponse<InventoryDtos.ProductShortage> firstPage =
                queries.getProductsShortage(warehouseId, null, 0, 1);
        assertEquals(2L, totalElements(firstPage));
        assertEquals(1, content(firstPage).size());
        InventoryDtos.ProductShortage first = content(firstPage).get(0);
        assertEquals(noBalanceProduct, first.productId());
        assertDecimal("0", first.physicalStock());
        assertDecimal("0", first.reservedStock());
        assertDecimal("0", first.availableStock());
        assertDecimal("8", first.minimumStock());
        assertDecimal("8", first.shortage());

        PageResponse<InventoryDtos.ProductShortage> secondPage = queries.getProductsShortage(warehouseId, null, 1, 1);

        assertEquals(2L, totalElements(secondPage));
        assertEquals(1, content(secondPage).size());
        InventoryDtos.ProductShortage second = content(secondPage).get(0);
        assertEquals(reservedProduct, second.productId());
        assertDecimal("12", second.physicalStock());
        assertDecimal("5", second.reservedStock());
        assertDecimal("7", second.availableStock());
        assertDecimal("10", second.minimumStock());
        assertDecimal("3", second.shortage());
    }

    // Datos independientes por test. @Transactional revierte todos los INSERT al finalizar.
    private long warehouse() {
        String code = "W-" + UUID.randomUUID().toString().substring(0, 12);
        return id("INSERT INTO warehouse(code, name) VALUES (?, ?) RETURNING id", code, code);
    }

    private long location(long warehouseId, String code) {
        return id("""
                INSERT INTO warehouse_location(warehouse_id, code)
                VALUES (?, ?) RETURNING id
                """, warehouseId, code);
    }

    private long product(String minimumStock, boolean active) {
        String sku = "P-" + UUID.randomUUID().toString().substring(0, 12);
        return id("""
                INSERT INTO product(sku, name, minimum_stock, active)
                VALUES (?, ?, ?, ?) RETURNING id
                """, sku, sku, new BigDecimal(minimumStock), active);
    }

    private long user() {
        String username = "test-" + UUID.randomUUID().toString().substring(0, 12);
        return id("""
                INSERT INTO security_user(username, email, password_hash)
                VALUES (?, ?, ?) RETURNING id
                """, username, username + "@example.test", "unused-in-repository-test");
    }

    private void balance(long productId, long locationId, String quantity, String reserved) {
        jdbc.update("""
                INSERT INTO inventory_balance(
                    product_id, warehouse_location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?)
                """, productId, locationId, new BigDecimal(quantity), new BigDecimal(reserved));
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    // Compatible tanto con PageResponse como record como con una clase convencional.
    @SuppressWarnings("unchecked")
    private static <T> List<T> content(PageResponse<T> page) {
        return (List<T>) ReflectionTestUtils.getField(page, "content");
    }

    private static long totalElements(PageResponse<?> page) {
        Object total = ReflectionTestUtils.getField(page, "totalElements");
        assertNotNull(total);
        return ((Number) total).longValue();
    }
}
