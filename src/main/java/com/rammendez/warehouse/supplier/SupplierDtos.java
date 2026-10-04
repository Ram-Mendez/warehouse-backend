package com.rammendez.warehouse.supplier;

import jakarta.validation.constraints.*;

public final class SupplierDtos {
    private SupplierDtos() {}

    public record Input(
            @NotBlank @Size(max = 60) String code,
            @NotBlank @Size(max = 180) String name,
            @Email @Size(max = 255) String email,
            @Size(max = 80) String phone,
            @NotNull Boolean active) {}

    public record Response(
            long id, String code, String name, String email, String phone, boolean active) {}
}
