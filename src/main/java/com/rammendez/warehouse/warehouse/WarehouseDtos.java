package com.rammendez.warehouse.warehouse;

import jakarta.validation.constraints.*;

public final class WarehouseDtos {
    private WarehouseDtos() {}

    public record Input(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 160) String name,
            @NotNull Boolean active) {}

    public record Response(long id, String code, String name, boolean active) {}

    public record LocationInput(
            @NotBlank @Size(max = 80) String code,
            @Size(max = 255) String description,
            @NotNull Boolean active) {}

    public record Location(
            long id, long warehouseId, String code, String description, boolean active) {}
}
