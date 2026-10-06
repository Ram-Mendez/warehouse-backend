package com.rammendez.warehouse.contact;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public final class ContactDtos {
    private ContactDtos() {}

    public enum Status {
        NEW,
        IN_PROGRESS,
        RESOLVED,
        SPAM
    }

    public record Input(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(min = 11, max = 10000) String message) {
        @Override
        public String toString() {
            return "ContactInput[redacted]";
        }
    }

    public record StatusInput(@NotNull Status status) {}

    public record Accepted(UUID id, Status status, Instant createdAt) {}

    public record Response(
            UUID id,
            String name,
            String email,
            String subject,
            String message,
            Status status,
            Long assignedTo,
            Instant createdAt,
            Instant resolvedAt) {
        @Override
        public String toString() {
            return "ContactResponse[id=" + id + "]";
        }
    }
}
