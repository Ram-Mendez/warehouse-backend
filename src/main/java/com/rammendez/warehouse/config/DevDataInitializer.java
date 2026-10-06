package com.rammendez.warehouse.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Profile("dev")
public class DevDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public DevDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        for (String code : List.of("WH-NORTH", "WH-SOUTH")) {
            jdbc.update(
                    "insert into warehouse(code,name) values (?,?) on conflict(code) do nothing",
                    code,
                    code.equals("WH-NORTH") ? "North warehouse" : "South warehouse");
            long id =
                    jdbc.queryForObject("select id from warehouse where code=?", Long.class, code);
            jdbc.update(
                    "insert into warehouse_location(warehouse_id,code,description) values"
                        + " (?,'DEFAULT','Default stock location') on conflict(warehouse_id,code)"
                        + " do nothing",
                    id);
        }
        for (String code : List.of("PACKAGING", "TOOLS")) {
            jdbc.update(
                    "insert into category(code,name) values (?,?) on conflict(code) do nothing",
                    code,
                    code.equals("PACKAGING") ? "Packaging" : "Tools");
        }
        for (String code : List.of("SUP-LOCAL", "SUP-GLOBAL")) {
            jdbc.update(
                    "insert into supplier(code,name,email) values (?,?,?) on conflict(code) do"
                            + " nothing",
                    code,
                    code.equals("SUP-LOCAL") ? "Local supplies" : "Global supplies",
                    code.toLowerCase() + "@example.test");
        }
        for (String sku : List.of("BOX-001", "TAPE-001", "TOOL-001")) {
            String category = sku.startsWith("TOOL") ? "TOOLS" : "PACKAGING";
            jdbc.update(
                    "insert into product(sku,name,category_id,unit) select ?,?,id,'UNIT' from"
                            + " category where code=? on conflict(sku) do nothing",
                    sku,
                    sku.equals("BOX-001")
                            ? "Cardboard box"
                            : sku.equals("TAPE-001") ? "Packing tape" : "Hand tool",
                    category);
            jdbc.update(
                    "insert into product_supplier(product_id,supplier_id,unit_cost,preferred)"
                        + " select p.id,s.id,2.5000,true from product p cross join supplier s where"
                        + " p.sku=? and s.code='SUP-LOCAL' on conflict(product_id,supplier_id) do"
                        + " nothing",
                    sku);
        }
        createDemoUserWithRoleAndWarehouseScopesIfAbsent("admin", "Admin-local-2026!", "ROLE_ADMIN", true);
        createDemoUserWithRoleAndWarehouseScopesIfAbsent("manager", "Manager-local-2026!", "ROLE_MANAGER", true);
        createDemoUserWithRoleAndWarehouseScopesIfAbsent("worker", "Worker-local-2026!", "ROLE_OPERATOR", false);
    }

    private void createDemoUserWithRoleAndWarehouseScopesIfAbsent(String username, String password, String role, boolean both) {
        var inserted =
                jdbc.query(
                        "insert into security_user(username,email,password_hash) values (?,?,?) on"
                                + " conflict(username) do nothing returning id",
                        (r, n) -> r.getLong(1),
                        username,
                        username + "@example.test",
                        passwords.encode(password));
        // Existing accounts are never reset or re-granted during restart.
        if (inserted.isEmpty()) {
            return;
        }
        long id = inserted.getFirst();
        jdbc.update(
                "insert into security_user_role(user_id,role_id) select ?,id from security_role"
                        + " where code=?",
                id,
                role);
        jdbc.update(
                "insert into security_user_warehouse_scope(user_id,warehouse_id,scope_role) select"
                        + " ?,id,? from warehouse where code='WH-NORTH' or (? and code='WH-SOUTH')",
                id,
                both ? "MANAGER" : "OPERATOR",
                both);
    }
}
