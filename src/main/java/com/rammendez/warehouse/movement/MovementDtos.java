package com.rammendez.warehouse.movement;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MovementDtos {
    private MovementDtos() {}

    public record StockInput(
            @Positive long warehouseId,
            @Positive long productId,
            Long locationId,
            @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @Size(max = 500) String reason,
            @Size(max = 160) String reference) {}

    public record Adjustment(
            @Positive long warehouseId,
            @Positive long productId,
            Long locationId,
            @NotNull @Digits(integer = 15, fraction = 4) BigDecimal delta,
            @NotBlank @Size(max = 500) String reason) {}

    public record Transfer(
            @Positive long sourceWarehouseId,
            @Positive long destinationWarehouseId,
            @Positive long productId,
            Long sourceLocationId,
            Long destinationLocationId,
            @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @Size(max = 500) String reason,
            @Size(max = 160) String reference) {}

    public record Compensation(@NotBlank @Size(max = 500) String reason) {}

    public record Line(
            long id,
            long productId,
            Long sourceLocationId,
            Long targetLocationId,
            BigDecimal quantity,
            BigDecimal unitCost) {}

    public record Response(
            UUID id,
            String movementNumber,
            MovementType type,
            MovementStatus status,
            Long sourceWarehouseId,
            Long targetWarehouseId,
            String reason,
            String reference,
            long createdBy,
            Long postedBy,
            Instant createdAt,
            Instant postedAt,
            UUID compensatesMovementId,
            UUID transferGroupId,
            UUID purchaseOrderId,
            List<Line> lines) {}

    public record Result(List<Response> movements) {}
}
