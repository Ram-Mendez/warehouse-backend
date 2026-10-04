package com.rammendez.warehouse.category;

import jakarta.validation.constraints.*;

public final class CategoryDtos {
    private CategoryDtos() {}

    public record Input(
            @NotBlank @Size(max = 60) String code,
            @NotBlank @Size(max = 160) String name,
            Long parentId,
            @NotNull Boolean active) {}

    public record Response(long id, String code, String name, Long parentId, boolean active) {}
}
