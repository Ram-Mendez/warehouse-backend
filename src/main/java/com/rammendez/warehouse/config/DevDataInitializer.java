package com.rammendez.warehouse.config;

import jakarta.persistence.EntityManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Profile("dev")
public class DevDataInitializer implements ApplicationRunner {
    private final EntityManager entityManager;
    private final PasswordEncoder passwords;

    public DevDataInitializer(EntityManager entityManager, PasswordEncoder passwords) {
        this.entityManager = entityManager;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        // PostgreSQL ON CONFLICT keeps dev seeding idempotent even across concurrent startups.
        for (String code : List.of("WH-NORTH", "WH-SOUTH")) {
            entityManager.createNativeQuery("""
                    insert into warehouse(code, name) values (:code, :name)
                    on conflict(code) do nothing
                    """)
                    .setParameter("code", code)
                    .setParameter("name", code.equals("WH-NORTH") ? "North warehouse" : "South warehouse")
                    .executeUpdate();
            entityManager.createNativeQuery("""
                    insert into warehouse_location(warehouse_id, code, description)
                    select id, 'DEFAULT', 'Default stock location' from warehouse where code = :code
                    on conflict(warehouse_id, code) do nothing
                    """)
                    .setParameter("code", code).executeUpdate();
        }
        for (String code : List.of("PACKAGING", "TOOLS")) {
            entityManager.createNativeQuery("""
                    insert into category(code, name) values (:code, :name)
                    on conflict(code) do nothing
                    """)
                    .setParameter("code", code)
                    .setParameter("name", code.equals("PACKAGING") ? "Packaging" : "Tools")
                    .executeUpdate();
        }
        for (String code : List.of("SUP-LOCAL", "SUP-GLOBAL")) {
            entityManager.createNativeQuery("""
                    insert into supplier(code, name, email) values (:code, :name, :email)
                    on conflict(code) do nothing
                    """)
                    .setParameter("code", code)
                    .setParameter("name", code.equals("SUP-LOCAL") ? "Local supplies" : "Global supplies")
                    .setParameter("email", code.toLowerCase() + "@example.test")
                    .executeUpdate();
        }
        for (String sku : List.of("BOX-001", "TAPE-001", "TOOL-001")) {
            String category = sku.startsWith("TOOL") ? "TOOLS" : "PACKAGING";
            entityManager.createNativeQuery("""
                    insert into product(sku, name, category_id, unit)
                    select :sku, :name, id, 'UNIT' from category where code = :category
                    on conflict(sku) do nothing
                    """)
                    .setParameter("sku", sku)
                    .setParameter("name", sku.equals("BOX-001") ? "Cardboard box"
                            : sku.equals("TAPE-001") ? "Packing tape" : "Hand tool")
                    .setParameter("category", category)
                    .executeUpdate();
            entityManager.createNativeQuery("""
                    insert into product_supplier(product_id, supplier_id, unit_cost, preferred)
                    select p.id, s.id, 2.5000, true from product p cross join supplier s
                    where p.sku = :sku and s.code = 'SUP-LOCAL'
                    on conflict(product_id, supplier_id) do nothing
                    """)
                    .setParameter("sku", sku).executeUpdate();
        }
        createDemoUserWithRoleAndWarehouseScopesIfAbsent("admin", "Admin-local-2026!", "ROLE_ADMIN", true);
        createDemoUserWithRoleAndWarehouseScopesIfAbsent("manager", "Manager-local-2026!", "ROLE_MANAGER", true);
        createDemoUserWithRoleAndWarehouseScopesIfAbsent("worker", "Worker-local-2026!", "ROLE_OPERATOR", false);
    }

    private void createDemoUserWithRoleAndWarehouseScopesIfAbsent(
            String username, String password, String role, boolean both) {
        var inserted = entityManager.createNativeQuery("""
                insert into security_user(username, email, password_hash)
                values (:username, :email, :hash)
                on conflict(username) do nothing returning id
                """, Long.class)
                .setParameter("username", username)
                .setParameter("email", username + "@example.test")
                .setParameter("hash", passwords.encode(password))
                .getResultList();
        // Existing accounts are never reset or re-granted during restart.
        if (inserted.isEmpty()) return;
        long id = ((Number) inserted.getFirst()).longValue();
        entityManager.createNativeQuery("""
                insert into security_user_role(user_id, role_id)
                select :userId, id from security_role where code = :role
                """)
                .setParameter("userId", id).setParameter("role", role).executeUpdate();
        entityManager.createNativeQuery("""
                insert into security_user_warehouse_scope(user_id, warehouse_id, scope_role)
                select :userId, id, :scopeRole from warehouse
                where code = 'WH-NORTH' or (:both and code = 'WH-SOUTH')
                """)
                .setParameter("userId", id)
                .setParameter("scopeRole", both ? "MANAGER" : "OPERATOR")
                .setParameter("both", both)
                .executeUpdate();
    }
}
