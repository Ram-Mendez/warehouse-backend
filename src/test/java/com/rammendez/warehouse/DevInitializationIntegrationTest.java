package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.rammendez.warehouse.config.DevDataInitializer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

@ActiveProfiles({"test", "dev"})
class DevInitializationIntegrationTest extends PostgresIntegrationTest {
    @Autowired DevDataInitializer initializer;

    @Test
    void demoInitializationIsIdempotentAndAllDevelopmentUsersCanLogin() throws Exception {
        long users = jdbc.queryForObject("select count(*) from security_user", Long.class);
        long products = jdbc.queryForObject("select count(*) from product", Long.class);
        initializer.run(new DefaultApplicationArguments(new String[0]));
        initializer.run(new DefaultApplicationArguments(new String[0]));
        assertThat(jdbc.queryForObject("select count(*) from security_user", Long.class))
                .isEqualTo(users);
        assertThat(jdbc.queryForObject("select count(*) from product", Long.class))
                .isEqualTo(products);
        for (var entry :
                Map.of(
                                "admin",
                                "Admin-local-2026!",
                                "manager",
                                "Manager-local-2026!",
                                "worker",
                                "Worker-local-2026!")
                        .entrySet()) {
            executeHttpRequestAndAssertStatus(
                    "POST",
                    "/api/v1/auth/login",
                    null,
                    Map.of("username", entry.getKey(), "password", entry.getValue()),
                    200);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from security_user_warehouse_scope s join"
                                        + " security_user u on u.id=s.user_id where"
                                        + " u.username='worker'",
                                Integer.class))
                .isEqualTo(1);
    }
}
