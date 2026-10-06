package com.rammendez.warehouse.inventory;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;

@Repository
public class InventoryRepository {
    public record Balance(
            long productId,
            long warehouseId,
            long locationId,
            BigDecimal quantity,
            BigDecimal reservedQuantity,
            BigDecimal availableQuantity,
            long version,
            Instant updatedAt) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public InventoryRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public void createInventoryBalanceIfMissing(long productId, long locationId) {
        jdbc.update(
                "insert into inventory_balance(product_id,warehouse_location_id) values (?,?) on"
                        + " conflict(product_id,warehouse_location_id) do nothing",
                productId,
                locationId);
    }

    public BigDecimal lockInventoryBalanceAndGetAvailableQuantity(long productId, long locationId) {
        return jdbc.queryForObject(
                "select quantity-reserved_quantity from inventory_balance where product_id=? and"
                        + " warehouse_location_id=? for update",
                BigDecimal.class,
                productId,
                locationId);
    }

    public void applyQuantityDeltaAndAdvanceBalanceVersion(long productId, long locationId, BigDecimal delta) {
        jdbc.update(
                "update inventory_balance set"
                    + " quantity=quantity+?,version=version+1,updated_at=now() where product_id=?"
                    + " and warehouse_location_id=?",
                delta,
                productId,
                locationId);
    }

    public InventoryService.Stock sumProductStockAcrossWarehouseLocations(long warehouseId, long productId) {
        return jdbc.queryForObject(
                "select coalesce(sum(b.quantity),0) quantity,coalesce(sum(b.reserved_quantity),0)"
                        + " reserved from inventory_balance b join warehouse_location l on"
                        + " l.id=b.warehouse_location_id where l.warehouse_id=? and b.product_id=?",
                (r, n) ->
                        new InventoryService.Stock(
                                warehouseId,
                                productId,
                                r.getBigDecimal("quantity"),
                                r.getBigDecimal("reserved"),
                                r.getBigDecimal("quantity").subtract(r.getBigDecimal("reserved"))),
                warehouseId,
                productId);
    }

    public PageResponse<Balance> listInventoryBalancesWithinUserScope(
            long userId, Long warehouseId, Long productId, int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var params = new HashMap<String, Object>();
        params.put("user", userId);
        String where =
                " from inventory_balance b join warehouse_location l on"
                        + " l.id=b.warehouse_location_id"
                        + " where exists(select 1 from security_user_warehouse_scope s"
                        + " where s.warehouse_id=l.warehouse_id"
                        + " and s.user_id=:user)";

        if (warehouseId != null) {
            where += " and l.warehouse_id=:warehouse";
            params.put("warehouse", warehouseId);
        }
        if (productId != null) {
            where += " and b.product_id=:product";
            params.put("product", productId);
        }
        long count = named.queryForObject("select count(*)" + where, params, Long.class);
        params.put("size", size);
        params.put("offset", offset);
        var content =
                named.query(
                        "select b.*,l.warehouse_id"
                                + where
                                + " order by l.warehouse_id,b.product_id,l.id limit :size offset"
                                + " :offset",
                        params,
                        (r, n) ->
                                new Balance(
                                        r.getLong("product_id"),
                                        r.getLong("warehouse_id"),
                                        r.getLong("warehouse_location_id"),
                                        r.getBigDecimal("quantity"),
                                        r.getBigDecimal("reserved_quantity"),
                                        r.getBigDecimal("quantity")
                                                .subtract(r.getBigDecimal("reserved_quantity")),
                                        r.getLong("version"),
                                        Sql.readNullableInstant(r, "updated_at")));
        return new PageResponse<>(content, count, page, size);
    }
}
