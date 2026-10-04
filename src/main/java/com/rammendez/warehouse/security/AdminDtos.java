package com.rammendez.warehouse.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Set;

public final class AdminDtos {
    private AdminDtos() {}

    public enum ScopeRole {
        VIEWER,
        OPERATOR,
        APPROVER,
        MANAGER
    }

    public record Create(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {
        @Override
        public String toString() {
            return "CreateUser[redacted]";
        }
    }

    public record Update(
            @Email @Size(max = 255) String email,
            Boolean enabled,
            Boolean locked,
            @Size(min = 12, max = 72) String password) {
        @Override
        public String toString() {
            return "UpdateUser[redacted]";
        }
    }

    public record Roles(@NotNull @Size(min = 1, max = 20) Set<@NotBlank String> roles) {}

    public record Scope(@Positive long warehouseId, @NotNull ScopeRole scopeRole) {}

    public record Scopes(@NotNull @Size(max = 100) List<@Valid Scope> scopes) {}

    public record UserSummary(
            long id, String username, String email, boolean enabled, boolean locked) {}

    public record UserResponse(
            long id,
            String username,
            String email,
            boolean enabled,
            boolean locked,
            List<String> roles,
            List<Scope> warehouseScopes) {}

    public record Role(long id, String code, String description) {}
}
