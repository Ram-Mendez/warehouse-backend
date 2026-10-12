package com.rammendez.warehouse.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

class JwtPropertiesTest {
    private static final String SECRET = "test-only-signing-secret-at-least-32-bytes";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(45);
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    @Test
    void bindsExistingExternalNamesAndRedactsSigningSecret() {
        var source = new MapConfigurationPropertySource(Map.of(
                "security.jwt.issuer", "warehouse-backend",
                "security.jwt.secret", SECRET,
                "security.jwt.access-token-ttl", "PT45M",
                "security.jwt.refresh-token-ttl", "P7D"));

        var properties = new Binder(source).bind("security.jwt", JwtProperties.class).get();

        assertThat(properties.issuer()).isEqualTo("warehouse-backend");
        assertThat(properties.accessTokenTtl()).isEqualTo(ACCESS_TTL);
        assertThat(properties.refreshTokenTtl()).isEqualTo(REFRESH_TTL);
        assertThat(properties.toString()).doesNotContain(SECRET);
    }

    @Test
    void rejectsMissingIssuerWeakKeyAndNonPositiveLifetimes() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new JwtProperties(" ", SECRET, ACCESS_TTL, REFRESH_TTL));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new JwtProperties("warehouse", "short", ACCESS_TTL, REFRESH_TTL));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new JwtProperties("warehouse", null, ACCESS_TTL, REFRESH_TTL));
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new JwtProperties("warehouse", SECRET, invalid, REFRESH_TTL));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new JwtProperties("warehouse", SECRET, ACCESS_TTL, invalid));
        }
    }

    @Test
    void signingKeyMinimumUsesUtf8BytesRatherThanCharacterCount() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new JwtProperties("warehouse", "a".repeat(31), ACCESS_TTL, REFRESH_TTL));
        var properties = new JwtProperties("warehouse", "é".repeat(16), ACCESS_TTL, REFRESH_TTL);
        assertThat(properties.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(32);
    }
}
