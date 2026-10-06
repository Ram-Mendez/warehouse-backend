package com.rammendez.warehouse.security;

import com.rammendez.warehouse.common.BusinessException;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static boolean isPasswordNonNullAndWithinBcryptByteLimit(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    public static void requirePasswordWithinBcryptByteLimit(String password) {
        if (!isPasswordNonNullAndWithinBcryptByteLimit(password)) {
            throw BusinessException.invalid("Password must be at most 72 UTF-8 bytes");
        }
    }
}
