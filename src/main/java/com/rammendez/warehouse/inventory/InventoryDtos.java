package com.rammendez.warehouse.inventory;

import java.math.BigDecimal;
import java.time.Instant;

public final class InventoryDtos {
    private InventoryDtos() {
    }

    public record Balance(
            long productId,
            long warehouseId,
            long locationId,
            BigDecimal quantity,
            BigDecimal reservedQuantity,
            BigDecimal availableQuantity,
            long version,
            Instant updatedAt) {
    }

    public record Stock(
            long warehouseId,
            long productId,
            BigDecimal quantity,
            BigDecimal reservedQuantity,
            BigDecimal availableQuantity) {
    }

    public record ProductShortage(
            long productId,
            BigDecimal minimumStock,
            BigDecimal physicalStock,
            BigDecimal reservedStock,
            BigDecimal availableStock,
            BigDecimal shortage) {
    }
}
