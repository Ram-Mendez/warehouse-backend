package com.rammendez.warehouse.inventory;

import com.rammendez.warehouse.common.*;
import com.rammendez.warehouse.product.ProductRepository;
import com.rammendez.warehouse.security.AccessControl;
import com.rammendez.warehouse.warehouse.WarehouseRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Transactional(readOnly = true)
public class InventoryService {
    public record Stock(
            long warehouseId,
            long productId,
            BigDecimal quantity,
            BigDecimal reservedQuantity,
            BigDecimal availableQuantity) {}

    private final InventoryRepository repository;
    private final AccessControl access;
    private final WarehouseRepository warehouses;
    private final ProductRepository products;

    public InventoryService(
            InventoryRepository repository,
            AccessControl access,
            WarehouseRepository warehouses,
            ProductRepository products) {
        this.repository = repository;
        this.access = access;
        this.warehouses = warehouses;
        this.products = products;
    }

    @PreAuthorize("hasAuthority('PERM_INVENTORY_READ')")
    public PageResponse<InventoryRepository.Balance> list(
            Long warehouseId, Long productId, int page, int size) {
        if (warehouseId != null) {
            access.warehouse(warehouseId, false);
            warehouses.get(warehouseId);
        }
        return repository.list(access.userId(), warehouseId, productId, page, size);
    }

    @PreAuthorize("hasAuthority('PERM_INVENTORY_READ')")
    public Stock stock(long warehouseId, long productId) {
        access.warehouse(warehouseId, false);
        warehouses.get(warehouseId);
        if (!products.existsById(productId)) {
            throw BusinessException.missing("Product");
        }
        return repository.stock(warehouseId, productId);
    }
}
