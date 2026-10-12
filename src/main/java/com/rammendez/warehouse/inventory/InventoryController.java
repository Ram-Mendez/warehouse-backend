package com.rammendez.warehouse.inventory;

import com.rammendez.warehouse.common.PageResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/inventory")
    public PageResponse<InventoryDtos.Balance> listInventoryBalancesWithinUserScope(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listInventoryBalancesWithinUserScope(warehouseId, productId, page, size);
    }

    @GetMapping("/warehouses/{warehouseId}/inventory")
    public PageResponse<InventoryDtos.Balance> listInventoryBalancesInWarehouse(
            @PathVariable long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listInventoryBalancesWithinUserScope(warehouseId, productId, page, size);
    }

    @GetMapping("/warehouses/{warehouseId}/inventory/{productId}")
    public InventoryDtos.Stock getProductStockTotalsInWarehouse(
            @PathVariable long warehouseId, @PathVariable long productId) {
        return service.getProductStockTotalsInWarehouse(warehouseId, productId);
    }

    @GetMapping("/warehouses/{warehouseId}/stock-shortages")
    public PageResponse<InventoryDtos.ProductShortage> getProductsShortagesInWarehouse(
            @PathVariable long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.getProductsShortages(warehouseId, categoryId, page, size);
    }
}
