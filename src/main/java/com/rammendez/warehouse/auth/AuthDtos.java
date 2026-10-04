package com.rammendez.warehouse.auth;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}

    public record Login(
            @NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }

    public record Refresh(@NotBlank @Size(min = 64, max = 64) String refreshToken) {
        @Override
        public String toString() {
            return "Refresh[redacted]";
        }
    }

    public record Tokens(
            String accessToken, String tokenType, Instant expiresAt, String refreshToken) {
        @Override
        public String toString() {
            return "Tokens[redacted]";
        }
    }

    public record Me(
            long id, String username, String email, List<String> roles, List<String> permissions) {}
}
