package com.rammendez.warehouse.inventory;

import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.product.ProductRepository;
import com.rammendez.warehouse.security.AccessControl;
import com.rammendez.warehouse.warehouse.WarehouseRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    private final InventoryRepository queries;
    private final AccessControl access;
    private final WarehouseRepository warehouses;
    private final ProductRepository products;

    public InventoryService(
            InventoryRepository queries,
            AccessControl access,
            WarehouseRepository warehouses,
            ProductRepository products) {
        this.queries = queries;
        this.access = access;
        this.warehouses = warehouses;
        this.products = products;
    }

    @PreAuthorize("hasAuthority('PERM_INVENTORY_READ')")
    public PageResponse<InventoryDtos.Balance> listInventoryBalancesWithinUserScope(
            Long warehouseId, Long productId, int page, int size) {
        if (warehouseId != null) {
            access.requireWarehouseScope(warehouseId, false);
            warehouses.getWarehouse(warehouseId);
        }
        return queries.listInventoryBalancesWithinUserScope(access.getAuthenticatedUserId(), warehouseId, productId, page, size);
    }

    @PreAuthorize("hasAuthority('PERM_INVENTORY_READ')")
    public InventoryDtos.Stock getProductStockTotalsInWarehouse(long warehouseId, long productId) {
        access.requireWarehouseScope(warehouseId, false);
        warehouses.getWarehouse(warehouseId);
        if (!products.existsById(productId)) {
            throw BusinessException.missing("Product");
        }
        return queries.sumProductStockAcrossWarehouseLocations(warehouseId, productId);
    }

    @PreAuthorize("hasAuthority('PERM_INVENTORY_READ')")
    public PageResponse<InventoryDtos.ProductShortage> getProductsShortages(
            long warehouseId, Long categoryId, int page, int size) {
        access.requireWarehouseScope(warehouseId, false);
        warehouses.getWarehouse(warehouseId);
        return queries.getProductsShortage(warehouseId, categoryId, page, size);
    }
}
