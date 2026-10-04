# Warehouse backend

## 1. What this application does

A warehouse API for catalogs, stock receipts/issues/transfers, purchases, immutable movement history, authentication, scoped access, user administration, and contact submissions. Swagger is the UI; there is no frontend.

## 2. Stack

Java **21**, Spring Boot **4.1.1**, Maven Wrapper **3.9.16**, PostgreSQL **18** (local container verified as **18.6**), Spring Data JPA/Hibernate, Spring JDBC, Spring Security OAuth2 Resource Server/JOSE, Flyway, Jakarta Validation, Actuator, springdoc OpenAPI **3.1.1**, and PostgreSQL Testcontainers. Maven manages the Spring, Hibernate, Flyway, and Testcontainers versions.

## 3. Start from zero: Windows PowerShell

Install JDK 21 and Docker Desktop with Linux containers. Set `JAVA_HOME`; the first build needs internet. In PowerShell:

```powershell
Set-Location C:\Users\balar\Desktop\warehouse-backend
java -version
.\mvnw.cmd -version
docker compose up -d --wait postgres
docker exec warehouse-postgres pg_isready -U warehouse -d warehouse
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

Once startup finishes, open another PowerShell window:

```powershell
Start-Process 'http://localhost:8080/swagger-ui/index.html'
Invoke-RestMethod 'http://localhost:8080/actuator/health'
```

Stop the backend with `Ctrl+C`. Stop the database with `docker compose stop postgres`; its volume retains data. Tests create a separate PostgreSQL container and never use the development database.

## 4. Database and application configuration

| Setting | Development value |
| --- | --- |
| Host / host port | `localhost` / `5434` |
| Database / username / password | `warehouse` / `warehouse` / `warehouse` |
| JDBC URL | `jdbc:postgresql://localhost:5434/warehouse` |
| Compose service / container | `postgres` / `warehouse-postgres` |
| Container port / image | `5432` / `postgres:18-alpine` |
| Volume | `warehouse-backend_warehouse_pgdata`, mounted at `/var/lib/postgresql` |
| Backend / Swagger | `8080` / `/swagger-ui/index.html` |
| OpenAPI JSON / health | `/v3/api-docs` / `/actuator/health` |

`application.yml` is canonical: Flyway validation, `ddl-auto=validate`, UTC, and Open Session in View disabled. `application-dev.yml` supplies local defaults/signing key; `src/test/resources/application-test.yml` uses Testcontainers connection overrides.

Outside `dev`, supply **DB_URL**, **DB_USERNAME**, **DB_PASSWORD**, and **JWT_SECRET** (strong random secret, minimum 32 UTF-8 bytes). `SERVER_PORT` defaults to 8080. Production seeds no accounts. Use `dev` locally only. Disable Swagger with `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false`.

Connections use a 5-second lock timeout and 30-second statement timeout. Health is public; `/actuator/info` and `/actuator/metrics` require `PERM_AUDIT_READ`. Every response carries `X-Correlation-Id`; safe incoming IDs are retained and also included in errors/log context.

## 5. Development users and business data

**THESE ARE DEVELOPMENT CREDENTIALS ONLY.** They exist only when the `dev` profile runs.

| Username | Password | Role | Warehouse access | Purpose |
| --- | --- | --- | --- | --- |
| `admin` | `Admin-local-2026!` | `ROLE_ADMIN` | North and South | Catalog, operations, security and support administration |
| `manager` | `Manager-local-2026!` | `ROLE_MANAGER` | North and South | Operational management, purchases and audit |
| `worker` | `Worker-local-2026!` | `ROLE_OPERATOR` | North only | Scoped stock operations and reads |

Seeder also creates `WH-NORTH`, `WH-SOUTH`, a `DEFAULT` location in each, categories `PACKAGING` and `TOOLS`, suppliers `SUP-LOCAL` and `SUP-GLOBAL`, and products `BOX-001`, `TAPE-001`, `TOOL-001`. Supplier unit costs are in `product_supplier`, not the product table. Initial balances are zero.

Restarts preserve existing records, passwords, roles and scopes. Discover IDs through GET. A new warehouse gets a DEFAULT location and its creator gets MANAGER scope; other accounts need explicit assignments.

## 6. Security in plain language

1. Login verifies the account/BCrypt password and creates a session.
2. The HS256 access JWT lasts **45 minutes**: send `Authorization: Bearer <accessToken>`.
3. Refresh tokens contain **48 secure random bytes**, expire after seven days, and are stored only as SHA-256 hashes.
4. Refresh consumes the current token and returns a replacement: `r1 → r2 → r3`. Each session is locked during rotation.
5. Reuse revokes the entire refresh family before returning 401. Avoid concurrent refresh retries.
6. Logout revokes the session/family; access JWTs naturally expire.

Roles grant database permissions. Every JWT request reloads account state/authorities. Disabled/locked accounts fail; account/role edits advance `auth_version` and revoke refresh families. Passwords are never returned; BCrypt accepts at most 72 UTF-8 bytes.

Permission and warehouse scope are separate checks, including for admins. VIEWER scopes allow reads; OPERATOR/APPROVER/MANAGER allow permitted writes. Transfers/history require both warehouse scopes; audits check all warehouses in OLD and NEW snapshots.

V2 seeds ADMIN/MANAGER/OPERATOR/AUDITOR/SUPPORT; V4 adds six permissions. ADMIN has all 26; MANAGER excludes user/contact administration. 401 means unauthenticated; 403 means permission/scope denied. Public login/refresh/contact ignore stale bearer headers.

## 7. All application tables

**23 application tables**, plus `flyway_schema_history`. Standalone tables have `id` primary keys; join tables have composite keys.

| Table | Purpose | Important columns | Relations |
| --- | --- | --- | --- |
| `security_user` | Accounts | username/email unique, password_hash, enabled, locked, auth_version | Roles, scopes, sessions and business actors |
| `security_role` | Reference roles | code unique, description | User-role and role-permission joins |
| `security_permission` | Reference permissions | code unique, description | Role-permission join |
| `security_user_role` | Account roles | user_id, role_id | User → role; composite PK |
| `security_role_permission` | Role grants | role_id, permission_id | Role → permission; composite PK |
| `security_user_warehouse_scope` | Resource access | user_id, warehouse_id, scope_role | User → warehouse; composite PK |
| `warehouse` | Sites | code unique, name, active, created_at | Locations, scopes, movements, purchases |
| `warehouse_location` | Stock bins | warehouse_id, code, active | Warehouse; unique warehouse/code |
| `category` | Catalog hierarchy | code unique, name, parent_id, active | Optional parent category |
| `supplier` | Suppliers | code unique, name, email, phone, active | Product links and purchases |
| `product` | Catalog | SKU/barcode unique, category_id, unit, minimum_stock, active, version, timestamps | Category |
| `product_supplier` | Supplier catalog/cost | product_id, supplier_id, supplier_product_code, unit_cost, preferred | Product → supplier; composite PK |
| `inventory_balance` | Current stock per bin | product_id, warehouse_location_id, quantity, reserved_quantity, version | Product/location; unique pair |
| `stock_movement` | Immutable posted ledger header | UUID, movement_number, type/status, warehouse IDs, actors/times, compensation/transfer/purchase linkage | Warehouses, users, original movement, purchase |
| `stock_movement_line` | Movement products/quantities | movement_id, product_id, source/target locations, quantity, unit_cost | Header, product, locations |
| `stock_movement_audit` | Database row-change evidence | movement_id, operation, actor, db_user, OLD/NEW JSON, changed_at | IDs intentionally have no FK, preserving deleted-draft history |
| `stock_reservation` | Existing reservation storage | product/location, quantity, status, reference, expiry | Product, location, creator; no reservation API yet |
| `purchase_order` | Purchasing workflow | UUID, order_number unique, supplier/warehouse, status, actors, version | Supplier, warehouse, users |
| `purchase_order_line` | Ordered/received quantities | purchase_order_id, product_id, ordered/received quantities, unit_cost | Order/product; unique order/product |
| `auth_session` | Refresh family | UUID, user_id, last_seen_at, revoked_at | User |
| `auth_refresh_token` | Hashed token lineage | UUID, session_id, token_hash unique, parent_token_id, expiry/use/revocation | Session; parent must belong to same session |
| `contact_message` | Contact submissions | UUID, name/email/subject/message, status, assignee, resolution | Optional assigned user |
| `audit_event` | Application events | actor, event/entity type/ID, JSON data, warehouse IDs, occurred_at | Actor and relevant warehouse scopes |

Database checks enforce nonnegative stock/cost, reserved ≤ stock, positive quantities, and received ≤ ordered. NUMERIC(19,4) quantities/costs map to BigDecimal. API timestamps are ISO-8601 instants.

## 8. Flyway and business history

| Migration | Change |
| --- | --- |
| V1 `baseline_schema` | Creates the 23 tables, keys, checks, indexes and pgcrypto extension |
| V2 `security_roles_permissions` | Five roles, initial 20 permissions and mappings |
| V3 `stock_movement_db_audit` | Actor lookup and AFTER INSERT/UPDATE/DELETE movement-header snapshots |
| V4 `operational_integrity` | Operational permissions, transfer/compensation/purchase links, ledger immutability/posting guards, indexes and refresh child uniqueness |
| V5 `scoped_audit_events` | Warehouse references/backfill/index for scoped application-event reads |
| V6 `ledger_relationship_guards` | Deferred transfer-pair validation, exact compensation/purchase line matching, refresh lineage/hash checks and one product per purchase |

Latest schema version: **6**. Applied migrations must never be edited; add V7 or later for evolution. Flyway clean is disabled.

`flyway_schema_history` tracks deployed database versions; movements/lines track business stock changes; `stock_movement_audit` captures header OLD/NEW snapshots; `audit_event` records application actions.

## 9. Business flows

| Operation | Actual flow |
| --- | --- |
| Receipt / return | HTTP → JWT + permission/scope → transactional service → lock/create bin balance → add quantity → draft header + line → POSTED → database/application audit → DTO |
| Issue | Same flow, subtracting only available stock (`quantity - reserved_quantity`); insufficient stock returns 409 |
| Adjustment | Signed, nonzero `delta` adds/removes stock; reason required; removal cannot consume reserved stock |
| Transfer | Check both warehouses → deterministic location/product locks → subtract source/add destination → linked OUT/IN headers → audit → one commit; any failure rolls everything back |
| Purchase receipt | Lock submitted/approved order → receive all unreceived lines through movement posting → mark quantities/RECEIVED → audit → one commit |
| Compensation | Lock original(s) → reverse exact lines in new COMPENSATION movements → preserve original; a transfer correction reverses both legs atomically |

Posting order is DRAFT header → lines → POSTED, all inside one transaction. PostgreSQL prevents later header/line changes, including adding lines to a posted header. One complete compensation is allowed per original; insufficient stock can prevent reversal. No movement edit/delete API exists.

Before header writes, `set_config('app.current_user_id', userId, true)` sets the authenticated actor on the transaction connection. Transaction-local context clears at commit/rollback. V3 still audits INSERT/UPDATE/DELETE of unposted headers.

## 10. Swagger quick start

Open **http://localhost:8080/swagger-ui/index.html**. Endpoints expose validated request DTOs, permissions and error responses.

1. Execute `POST /api/v1/auth/login`:

   ```json
   {"username":"admin","password":"Admin-local-2026!"}
   ```

2. Copy `accessToken`. Click **Authorize**, paste just the token, and authorize the `bearerAuth` scheme. Store `refreshToken` separately if trying refresh.
3. GET `/warehouses`, `/products?sku=BOX-001`, `/categories`, and `/suppliers`. Note returned warehouse/product IDs.
4. Receive 10 units using the discovered IDs:

   ```json
   {"warehouseId":1,"productId":1,"quantity":10,"reason":"Initial delivery"}
   ```

   Replace example IDs. Omitting `locationId` uses that warehouse's DEFAULT bin.

5. GET `/warehouses/{warehouseId}/inventory/{productId}`; quantity should rise by 10.
6. POST `/transfers`:

   ```json
   {"sourceWarehouseId":1,"destinationWarehouseId":2,"productId":1,"quantity":3,"reason":"Replenish South"}
   ```

7. POST `/movements/issue` with source warehouse, product, quantity 2 and a reason. Inspect both balances.
8. GET `/movements?productId=...`, `/audit/stock-movements`, and `/audit/events`.
9. To create a product, POST `/products` with `sku`, `name`, `unit`, `minimumStock:0`, `active:true`, and optional category/supplier IDs/unit cost. GET `/products/{id}/suppliers` shows linked costs. PUT requires the current product `version` from GET.
10. For purchasing: POST order (`supplierId`, `warehouseId`, `orderNumber`), POST its `/lines` (`productId`, `quantity`, optional `unitCost`), POST `/submit`, optionally `/approve`, then `/receive`. Receiving again returns 409.

Refresh returns new access **and** refresh tokens; update both. Reusing the old refresh token deliberately revokes the session. Use the worker account to demonstrate South warehouse 403.

## 11. Endpoint map

All business routes start with `/api/v1`. Permission names below carry the `PERM_` prefix. Warehouse reads/writes also enforce scope; lists are filtered to accessible resources.

| Module | Endpoints | Permission |
| --- | --- | --- |
| Auth | POST `/auth/login`, `/auth/refresh`; POST `/auth/logout`; GET `/auth/me` | First two public; others JWT |
| Categories | GET `/categories`, `/{id}`; POST `/categories`; PUT `/{id}` | CATEGORY_READ / CATEGORY_WRITE |
| Suppliers | GET `/suppliers`, `/{id}`; POST `/suppliers`; PUT `/{id}` | SUPPLIER_READ / SUPPLIER_WRITE |
| Warehouses | GET `/warehouses`, `/{id}`; POST `/warehouses`; PUT `/{id}` | WAREHOUSE_READ / WAREHOUSE_MANAGE |
| Locations | GET `/warehouses/{w}/locations`, `/{id}`; POST locations; PUT `/{id}` | WAREHOUSE_READ / WAREHOUSE_MANAGE |
| Products | GET `/products`, `/{id}`, `/{id}/suppliers`; POST products; PUT `/{id}` | PRODUCT_READ / PRODUCT_WRITE |
| Inventory | GET `/inventory`; GET `/warehouses/{w}/inventory`, `/{productId}` | INVENTORY_READ |
| Stock | POST `/movements/receipt`, `/return`; POST `/issue`; POST `/adjustment` | STOCK_RECEIVE / STOCK_ISSUE / INVENTORY_ADJUST |
| Transfers | POST `/transfers` | STOCK_TRANSFER + both warehouse scopes |
| Movements | GET `/movements`, `/{id}`; POST `/{id}/compensate` | MOVEMENT_READ / INVENTORY_ADJUST |
| Purchases | POST/GET `/purchase-orders`; GET `/{id}`; POST `/{id}/lines`, `/submit`, `/cancel` | PURCHASE_CREATE / PURCHASE_READ |
| Purchase approval/receipt | POST `/purchase-orders/{id}/approve`, `/receive` | PURCHASE_APPROVE / PURCHASE_RECEIVE |
| Contact | POST `/contact`; GET `/contact`, `/{id}`; PATCH `/{id}/status` | Public submission; CONTACT_READ / CONTACT_MANAGE |
| Audit | GET `/audit/stock-movements`, `/audit/movements/{id}`, `/audit/events` | AUDIT_READ + relevant scopes; user/contact events also require their management/read permissions |
| Admin | GET/POST `/admin/users`; GET/PATCH `/{id}`; PUT `/{id}/roles`, `/{id}/warehouse-scopes`; GET `/admin/roles` | USER_MANAGE |

List parameters: `page=0`, `size=20` (maximum 100). Product filters: `search`, exact `sku`, `categoryId`, `supplierId`, `active`; sorts: `id`, `name`, `sku`, `createdAt` (newest first). Search treats `%`, `_`, and backslash literally. Inventory filters warehouse/product; movement filters warehouse/product/type; purchase filters warehouse/status; audit filters movement ID or entity type/ID. Purchase lists return summaries; detail includes lines.

PUT catalog endpoints replace supplied state; `active:false` deactivates records. Hard deletion is not exposed. Contact status: NEW, IN_PROGRESS, RESOLVED, SPAM. Purchase workflow: DRAFT → SUBMITTED → optional APPROVED → RECEIVED; cancellable before receipt. Partial receipt is intentionally not implemented.

Errors use ProblemDetail with HTTP status, safe detail, path, timestamp and correlation ID. Validation/malformed input: 400; authentication: 401; permission/scope: 403; absent resource: 404; duplicates, insufficient stock, state/version/lock conflicts: 409. Unexpected failures: 500 without stack traces, SQL or credentials in the response.

## 12. Project structure

Under `src/main/java/com/rammendez/warehouse`:

| Package | Responsibility |
| --- | --- |
| `auth` | Login, JWT issuance, refresh families/rotation and logout |
| `security` | Account/role/permission persistence, scopes, password policy and admin APIs |
| `category`, `supplier`, `product` | Catalog services, validation and persistence |
| `warehouse` | Warehouses and stock locations |
| `inventory` | Balance reads and explicit balance-lock/update SQL |
| `movement` | Transactional posting, transfer and compensation ledger |
| `purchase` | Order lifecycle and full receipt |
| `contact` | Public submissions and protected support management |
| `audit` | Transaction actor, application events and scoped audit reads |
| `common` | Errors, pagination, SQL helpers and correlation filter |
| `config` | Security chain/JWT beans, OpenAPI and dev-only initializer |

Controllers handle HTTP/validation; services own authorization/transactions; repositories persist. Product CRUD uses JPA versions; ledger/security/queries use explicit JDBC on the same Spring-managed transaction. APIs return DTOs/projections. Migrations: `src/main/resources/db/migration`; tests: `src/test/java`; `.codex`: agent instructions.

## 13. Testing

```powershell
.\mvnw.cmd clean test
```

Docker must run. Tests create clean PostgreSQL 18, migrate and validate JPA, then exercise real HTTP/security/service/SQL behavior: auth/refresh/logout, permission/scope denial, catalog CRUD/literal search/versioning, stock races/transfers/rollback, audit actor/snapshots/immutability/compensation, purchases/double receipt, contacts, admin replacement races and idempotent seeding. Latches, observed database locks and bounded waits coordinate races. No H2.

Final verification: **59 tests passed, zero failures/errors/skips**, plus **46 live HTTP checks** against the development database. Swagger exposes **58 API operations**.

## 14. Troubleshooting

| Symptom | Action |
| --- | --- |
| Port 5434 busy | `Get-NetTCPConnection -LocalPort 5434`; stop the conflicting service or change Compose port and `DB_URL` together |
| PostgreSQL not healthy | `docker compose ps`; `docker compose logs postgres`; rerun `pg_isready` |
| Docker not running / Testcontainers fails | Start Docker Desktop in Linux-container mode; check `docker version`; allow image downloads and inspect `target/surefire-reports` |
| Backend port 8080 busy | Stop the previous backend or set `$env:SERVER_PORT='8081'`, then use that port in URLs |
| 401 | Login again; check Bearer token and expiry/account status. A reused/expired/revoked refresh token needs a fresh login |
| 403 | Check the required permission AND warehouse scopes. Worker has North only; admins also need explicit scopes |
| Flyway checksum mismatch | Restore the original applied migration from source control and put changes in a new migration. Do not disable validation or casually repair history |
| Swagger missing | Confirm dev startup succeeded and the URL/port; check OpenAPI has not been disabled; dependency is springdoc 3.x for Boot 4 |
| Missing DB/JWT placeholders | Enable `dev` locally or supply the four required production environment variables |
| Insufficient stock / lock conflict | Read available stock, check reservations, and retry only after resolving the conflict; purchase receive is state-protected |

Do not use `docker compose down -v` for routine recovery: it deletes the persistent development database.

## 15. Architectural snapshot

- PostgreSQL is the stock source of truth; service transactions update balances and history together.
- Deterministic balance creation/lock ordering prevents oversell and opposite-transfer deadlocks.
- Reservations reduce available stock even though reservation management has no API yet.
- Transfer pairing is checked again by a deferred database constraint trigger at commit.
- Posted movement headers and lines are protected in PostgreSQL; corrections append exact reversals.
- Purchase receiving is full-order, locked, uniquely linked and performed once.
- JPA product versions also advance for supplier-only edits.
- Category hierarchy edits serialize through a transaction advisory lock.
- Account locks serialize login with administrative changes; refresh families lock their sessions.
- Refresh reuse revocation commits before its 401 response.
- Current database permissions/account state supplement signed JWT identity.
- Warehouse scope filters apply to list/detail/history and both audit snapshots.
- Transaction-local actor context cannot leak through the connection pool.
- UTC DTO timestamps, BigDecimal quantities/costs, parameter binding and literal LIKE search are explicit.
- Audit/security APIs return no passwords, token hashes, signing secrets or contact-body audit payloads.
- Runtime logging avoids request bodies and credentials; correlation IDs connect safe errors/logs.

Limits: full purchase receipts and compensations only; no reservation API, generic stock-request idempotency key, or custom role/permission editing. Repeating manual stock requests creates new movements. Production needs provisioned secrets/deployment controls; demo credentials/defaults are development-only.
