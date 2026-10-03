# AGENTS.md

## Stack
- Java 21
- Spring Boot 4.1.1
- Maven Wrapper
- PostgreSQL 18
- Spring Data JPA
- Spring Security
- OAuth2 Resource Server / JOSE for JWT
- Flyway only for schema/data migrations

## Non-negotiable database rules
- `spring.jpa.hibernate.ddl-auto=validate`. Never use create/update.
- Every schema/reference-data change gets a NEW Flyway migration. Never edit an applied migration.
- Flyway history answers: "what database version/change was deployed?"
- Runtime business history answers: `stock_movement`, `stock_movement_audit`, and `audit_event`.
- A posted stock movement is business history: never silently rewrite or delete it. Corrections are compensating movements.
- Inventory mutations must be transactional and concurrency-safe. Use optimistic/pessimistic locking deliberately and tests that prove it.
- Before stock_movement INSERT/UPDATE/DELETE, set PostgreSQL transaction-local `app.current_user_id` from the authenticated user so the DB trigger records the actor.

## Security target
- DB-backed users, roles and permissions.
- Warehouse-level resource scope via `security_user_warehouse_scope` in addition to global permissions.
- Access JWT TTL: 45 minutes.
- Refresh tokens are opaque 48-byte secure random values; store SHA-256 hash only.
- Refresh tokens are one-time-use and rotate: r1 -> r2 -> r3.
- Reuse of an already-used refresh token revokes the complete auth session.
- Logout revokes the session/refresh family. Access token naturally expires.
- Method security with permission checks AND warehouse scope checks where applicable.
- Passwords: BCrypt.
- Never put raw refresh tokens, passwords, JWT signing secrets, or personal contact-message content in logs.

## Package style
Package-by-feature: auth, security, product, category, supplier, warehouse, inventory, movement, purchase, contact, audit, common.
Keep controllers thin. Services own transactions and business invariants.
DTOs at API boundary; entities are not API contracts.

## Testing
- Integration tests against PostgreSQL/Testcontainers for security, Flyway, locking, transactions and movement posting.
- Minimum critical tests: refresh rotation/reuse, role+permission checks, warehouse scope 403, concurrent stock decrement, transfer atomicity, movement audit trigger, posted-movement immutability/compensation.
- Run `./mvnw test` (Windows: `mvnw.cmd test`) before declaring complete.
