package com.rammendez.warehouse.common;

public final class Sql {
    private Sql() {}

    public static String createEscapedContainsLikePattern(String input) {
        // Parameter binding prevents SQL injection; escaping also makes %, _ and backslash
        // literal search characters instead of allowing them to broaden LIKE matches.
        return "%" + input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
