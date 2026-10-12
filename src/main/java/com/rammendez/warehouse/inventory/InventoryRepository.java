package com.rammendez.warehouse.inventory;

import com.rammendez.warehouse.common.BusinessException;
import com.rammendez.warehouse.common.PageResponse;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.*;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryBalance, Long> {
    // PostgreSQL's conflict handling serializes concurrent creation of the same stock key.
    @Modifying
    @Query(value = """
            insert into inventory_balance(product_id, warehouse_location_id)
            values (:productId, :locationId)
            on conflict(product_id, warehouse_location_id) do nothing
            """, nativeQuery = true)
    void createInventoryBalanceIfMissing(long productId, long locationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBalance b where b.productId = :productId and b.locationId = :locationId")
    Optional<InventoryBalance> lockBalance(long productId, long locationId);

    default BigDecimal lockInventoryBalanceAndGetAvailableQuantity(long productId, long locationId) {
        var balance = lockBalance(productId, locationId).orElseThrow(() -> BusinessException.missing("Inventory balance"));
        return balance.quantity.subtract(balance.reservedQuantity);
    }

    // Bulk arithmetic preserves the original atomic increment, including a zero delta.
    // Flush and clear prevent managed balances from retaining an obsolete quantity/version;
    // PostgreSQL row locks remain held until the surrounding service transaction completes.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InventoryBalance b
            set b.quantity = b.quantity + :delta, b.version = b.version + 1, b.updatedAt = instant
            where b.productId = :productId and b.locationId = :locationId
            """)
    void applyQuantityDeltaAndAdvanceBalanceVersion(long productId, long locationId, BigDecimal delta);

    interface StockTotals {
        BigDecimal getQuantity();
        BigDecimal getReservedQuantity();
    }

    @Query("""
            select coalesce(sum(distinct b.quantity), 0) as quantity,
                   coalesce(sum(b.reservedQuantity), 0) as reservedQuantity
            from InventoryBalance b join WarehouseLocation l on l.id = b.locationId
            where l.warehouse.id = :warehouseId and b.productId = :productId
            """)
    StockTotals sumStock(long warehouseId, long productId);

    default InventoryDtos.Stock sumProductStockAcrossWarehouseLocations(long warehouseId, long productId) {
        var totals = sumStock(warehouseId, productId);
        return new InventoryDtos.Stock(warehouseId, productId, totals.getQuantity(),
                totals.getReservedQuantity(), totals.getQuantity().subtract(totals.getReservedQuantity()));
    }

    interface BalanceView {
        long getProductId();
        long getWarehouseId();
        long getLocationId();
        BigDecimal getQuantity();
        BigDecimal getReservedQuantity();
        long getVersion();
        Instant getUpdatedAt();
    }

    @Query("""
            select b.productId as productId, l.warehouse.id as warehouseId,
                   l.id as locationId, b.quantity as quantity, b.reservedQuantity as reservedQuantity,
                   b.version as version, b.updatedAt as updatedAt
            from InventoryBalance b join WarehouseLocation l on l.id = b.locationId
            where exists (select 1 from WarehouseScope s
                          where s.warehouseId = l.warehouse.id and s.userId = :userId)
              and (:warehouseId is null or l.warehouse.id = :warehouseId)
              and (:productId is null or b.productId = :productId)
            order by l.warehouse.id, b.productId, l.id
            """)
    Page<BalanceView> findScopedBalances(long userId, Long warehouseId, Long productId, Pageable pageable);

    default PageResponse<InventoryDtos.Balance> listInventoryBalancesWithinUserScope(
            long userId, Long warehouseId, Long productId, int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = findScopedBalances(userId, warehouseId, productId, PageRequest.of(page, size));
        return new PageResponse<>(result.stream().map(b -> new InventoryDtos.Balance(
                b.getProductId(), b.getWarehouseId(), b.getLocationId(), b.getQuantity(),
                b.getReservedQuantity(), b.getQuantity().subtract(b.getReservedQuantity()),
                b.getVersion(), b.getUpdatedAt())).toList(), result.getTotalElements(), page, size);
    }

    interface ShortageView {
        long getProductId();
        BigDecimal getMinimumStock();
        BigDecimal getPhysicalStock();
        BigDecimal getReservedStock();
        BigDecimal getAvailableStock();
        BigDecimal getShortage();
    }

    String SHORTAGE_FILTER = """
            from product p
            left join (
                select b.product_id, sum(b.quantity) as physical,
                       sum(b.reserved_quantity) as reserved,
                       sum(b.quantity - b.reserved_quantity) as available
                from inventory_balance b
                join warehouse_location l on l.id = b.warehouse_location_id
                where l.warehouse_id = :warehouseId
                group by b.product_id
            ) s on s.product_id = p.id
            where p.active = true and coalesce(s.available, 0) < p.minimum_stock
              and (cast(:categoryId as bigint) is null or p.category_id = :categoryId)
            """;

    @Query(value = """
            select p.id as productId, p.minimum_stock as minimumStock,
                   coalesce(s.physical, 0) as physicalStock,
                   coalesce(s.reserved, 0) as reservedStock,
                   coalesce(s.available, 0) as availableStock,
                   p.minimum_stock - coalesce(s.available, 0) as shortage
            """ + SHORTAGE_FILTER + " order by shortage desc, p.id",
            countQuery = "select count(*) " + SHORTAGE_FILTER, nativeQuery = true)
    Page<ShortageView> findShortages(long warehouseId, Long categoryId, Pageable pageable);

    default PageResponse<InventoryDtos.ProductShortage> getProductsShortage(
            long warehouseId, Long categoryId, int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var result = findShortages(warehouseId, categoryId, PageRequest.of(page, size));
        return new PageResponse<>(result.stream().map(s -> new InventoryDtos.ProductShortage(
                s.getProductId(), s.getMinimumStock(), s.getPhysicalStock(), s.getReservedStock(),
                s.getAvailableStock(), s.getShortage())).toList(), result.getTotalElements(), page, size);
    }
}
