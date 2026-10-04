package com.rammendez.warehouse.product;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public final class ProductDtos {
    private ProductDtos() {}

    public record Input(
            @NotBlank @Size(max = 80) String sku,
            @Size(max = 120) String barcode,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 10000) String description,
            Long categoryId,
            @NotBlank @Size(max = 30) String unit,
            @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal minimumStock,
            @NotNull Boolean active,
            Long supplierId,
            @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
            Long version) {}

    public record Response(
            long id,
            String sku,
            String barcode,
            String name,
            String description,
            Long categoryId,
            String unit,
            BigDecimal minimumStock,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    public record SupplierLink(
            long supplierId,
            String supplierCode,
            String supplierName,
            String supplierProductCode,
            BigDecimal unitCost,
            boolean preferred) {}
}
