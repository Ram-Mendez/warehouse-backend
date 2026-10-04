package com.rammendez.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc(
        print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE,
        printOnlyOnFailure = false)
@ActiveProfiles("test")
abstract class PostgresIntegrationTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    static final String PASSWORD = "Integration-password-2026!";
    static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    long north,
            south,
            product,
            category,
            supplier,
            adminId,
            workerId,
            managerId,
            northLocation,
            southLocation;
    String adminName, workerName, managerName, admin, worker, manager, suffix;

    @BeforeEach
    void fixture() throws Exception {
        suffix = UUID.randomUUID().toString();
        north = warehouse("N-" + suffix);
        south = warehouse("S-" + suffix);
        northLocation = location(north);
        southLocation = location(south);
        category =
                jdbc.queryForObject(
                        "insert into category(code,name) values (?,?) returning id",
                        Long.class,
                        "C-" + suffix,
                        "Fixture category");
        supplier =
                jdbc.queryForObject(
                        "insert into supplier(code,name) values (?,?) returning id",
                        Long.class,
                        "S-" + suffix,
                        "Fixture supplier");
        product =
                jdbc.queryForObject(
                        "insert into product(sku,name,category_id,unit) values (?, ?,?,'UNIT')"
                                + " returning id",
                        Long.class,
                        "P-" + suffix,
                        "Fixture product",
                        category);
        adminName = "admin-" + suffix;
        workerName = "worker-" + suffix;
        managerName = "manager-" + suffix;
        adminId = user(adminName, "ROLE_ADMIN");
        workerId = user(workerName, "ROLE_OPERATOR");
        managerId = user(managerName, "ROLE_MANAGER");
        scope(adminId, north, "MANAGER");
        scope(adminId, south, "MANAGER");
        scope(managerId, north, "MANAGER");
        scope(managerId, south, "MANAGER");
        scope(workerId, north, "OPERATOR");
        admin = login(adminName).path("accessToken").asText();
        worker = login(workerName).path("accessToken").asText();
        manager = login(managerName).path("accessToken").asText();
    }

    long warehouse(String code) {
        return jdbc.queryForObject(
                "insert into warehouse(code,name) values (?,'Fixture warehouse') returning id",
                Long.class,
                code);
    }

    long location(long warehouse) {
        return jdbc.queryForObject(
                "insert into warehouse_location(warehouse_id,code) values (?,'DEFAULT') returning"
                        + " id",
                Long.class,
                warehouse);
    }

    long user(String username, String role) {
        long id =
                jdbc.queryForObject(
                        "insert into security_user(username,email,password_hash) values (?,?,?)"
                                + " returning id",
                        Long.class,
                        username,
                        username + "@example.test",
                        PASSWORD_HASH);
        jdbc.update(
                "insert into security_user_role(user_id,role_id) select ?,id from security_role"
                        + " where code=?",
                id,
                role);
        return id;
    }

    void scope(long user, long warehouse, String role) {
        jdbc.update(
                "insert into security_user_warehouse_scope(user_id,warehouse_id,scope_role) values"
                        + " (?,?,?)",
                user,
                warehouse,
                role);
    }

    JsonNode login(String username) throws Exception {
        return json(
                call(
                        "POST",
                        "/api/v1/auth/login",
                        null,
                        Map.of("username", username, "password", PASSWORD),
                        200));
    }

    MvcResult call(String method, String path, String token, Object body, int status)
            throws Exception {
        var builder =
                MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method),
                        java.net.URI.create(path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.contentType("application/json").content(mapper.writeValueAsString(body));
        }
        var result = mvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(method + " " + path + " status")
                .isEqualTo(status);
        return result;
    }

    JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    Map<String, Object> stock(long warehouse, BigDecimal quantity) {
        return Map.of(
                "warehouseId",
                warehouse,
                "productId",
                product,
                "quantity",
                quantity,
                "reason",
                "Integration stock operation");
    }

    Map<String, Object> transfer(BigDecimal quantity) {
        return Map.of(
                "sourceWarehouseId",
                north,
                "destinationWarehouseId",
                south,
                "productId",
                product,
                "quantity",
                quantity,
                "reason",
                "Integration transfer");
    }

    JsonNode receipt(BigDecimal quantity) throws Exception {
        return json(call("POST", "/api/v1/movements/receipt", admin, stock(north, quantity), 201));
    }

    BigDecimal balance(long warehouse) {
        return jdbc.queryForObject(
                "select coalesce(sum(b.quantity),0) from inventory_balance b join"
                    + " warehouse_location l on l.id=b.warehouse_location_id where b.product_id=?"
                    + " and l.warehouse_id=?",
                BigDecimal.class,
                product,
                warehouse);
    }

    Map<String, Object> productInput(String sku, String name) {
        return Map.of(
                "sku",
                sku,
                "name",
                name,
                "categoryId",
                category,
                "supplierId",
                supplier,
                "unit",
                "UNIT",
                "minimumStock",
                0,
                "active",
                true,
                "unitCost",
                2.50);
    }
}
