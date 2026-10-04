package com.rammendez.warehouse.security;

public record UserAccount(
        long id,
        String username,
        String email,
        String passwordHash,
        boolean enabled,
        boolean locked,
        long authVersion) {
    @Override
    public String toString() {
        return "UserAccount[id=" + id + ", enabled=" + enabled + "]";
    }
}
