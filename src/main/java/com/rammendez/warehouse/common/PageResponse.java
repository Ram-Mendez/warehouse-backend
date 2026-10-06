package com.rammendez.warehouse.common;

import java.util.List;

public record PageResponse<T>(List<T> content, long totalElements, int page, int size) {
    public static int validatePageBoundsAndCalculateOffset(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw BusinessException.invalid("page must be 0..100000 and size must be 1..100");
        }
        return page * size;
    }
}
