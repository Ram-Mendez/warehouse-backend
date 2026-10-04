package com.rammendez.warehouse.purchase;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PurchaseDtos {
    private PurchaseDtos() {}

    public record Input(
            @Positive long supplierId,
            @Positive long warehouseId,
            @NotBlank @Size(max = 80) String orderNumber) {}

    public record LineInput(
            @Positive long productId,
            @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal unitCost) {}

    public record Line(
            long id,
            long productId,
            BigDecimal orderedQuantity,
            BigDecimal receivedQuantity,
            BigDecimal unitCost) {}

    public record Response(
            UUID id,
            String orderNumber,
            long supplierId,
            long warehouseId,
            PurchaseStatus status,
            long createdBy,
            long version,
            Instant createdAt,
            List<Line> lines) {}

    public record Summary(
            UUID id,
            String orderNumber,
            long supplierId,
            long warehouseId,
            PurchaseStatus status,
            long createdBy,
            long version,
            Instant createdAt) {}
}
