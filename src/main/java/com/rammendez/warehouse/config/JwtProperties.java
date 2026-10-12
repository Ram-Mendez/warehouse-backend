package com.rammendez.warehouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

// Binding validates once at startup, before an invalid TTL or signing key can issue tokens.
@ConfigurationProperties("security.jwt")
public record JwtProperties(
        String issuer, String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer must not be blank");
        }
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        requirePositiveDuration(accessTokenTtl, "JWT access token TTL");
        requirePositiveDuration(refreshTokenTtl, "JWT refresh token TTL");
    }

    private static void requirePositiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    // Records otherwise print every component, including the signing secret.
    @Override
    public String toString() {
        return "JwtProperties[redacted]";
    }
}
