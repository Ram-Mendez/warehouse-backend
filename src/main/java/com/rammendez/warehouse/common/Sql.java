package com.rammendez.warehouse.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public final class Sql {
    private Sql() {}

    public static <T> T firstRowOrThrowNotFound(List<T> rows, String resource) {
        if (rows.isEmpty()) {
            throw BusinessException.missing(resource);
        }
        return rows.getFirst();
    }

    public static Long nullableLong(ResultSet row, String column) throws SQLException {
        return row.getObject(column, Long.class);
    }

    public static Instant readNullableInstant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static String createEscapedContainsLikePattern(String input) {
        return "%" + input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
