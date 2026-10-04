package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WarehouseBackendApplicationTests extends PostgresIntegrationTest {

    @Test
    void contextLoads() {
        assertThat(
                        jdbc.queryForObject(
                                "select max(version::int) from flyway_schema_history where success",
                                Integer.class))
                .isEqualTo(6);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from information_schema.tables where"
                                        + " table_schema='public' and table_name <>"
                                        + " 'flyway_schema_history'",
                                Integer.class))
                .isEqualTo(23);
    }
}
