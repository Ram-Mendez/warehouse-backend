package com.rammendez.warehouse;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class SupplierIntegrationTest extends PostgresIntegrationTest {

    @Test
    void update() throws Exception {

        // 1. Proveedor creado automáticamente: supplier
        var input = createSupplier();

        // 2. Ejecutar PUT para modificarlo
        executeHttpRequestAndAssertStatus(
                "PUT",
                "/api/v1/suppliers/" + supplier,
                admin,
                input,
                200
        );

        // 3. Consultar PostgreSQL con jdbc
        var row = jdbc.queryForMap(
                "SELECT code, name, email, active FROM supplier WHERE id = ?",
                supplier
        );

        // 4. Comprobar los valores guardados
        assertThat(row.get("code")).isEqualTo("SUP-" + suffix);
        assertThat(row.get("name")).isEqualTo("Proveedor actualizado");
        assertThat(row.get("email")).isEqualTo("orders@example.test");
        assertThat(row.get("active")).isEqualTo(true);
    }

    private Object createSupplier() {
        return Map.of("code", "SUP-" + suffix, "name", "Proveedor actualizado", "email", "orders@example.test", "active", true);
    }
}