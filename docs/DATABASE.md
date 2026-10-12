# Database reference

## Tables

**24 application tables**, plus `flyway_schema_history`. Standalone tables have `id` primary keys; join tables have composite keys.

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
| `product_replenishment_policy` | Existing replenishment settings storage | product_id, warehouse_id, minimum_stock, target_stock, active | Product/warehouse; unique pair; no policy API yet |
| `purchase_order` | Purchasing workflow | UUID, order_number unique, supplier/warehouse, status, actors, version | Supplier, warehouse, users |
| `purchase_order_line` | Ordered/received quantities | purchase_order_id, product_id, ordered/received quantities, unit_cost | Order/product; unique order/product |
| `auth_session` | Refresh family | UUID, user_id, last_seen_at, revoked_at | User |
| `auth_refresh_token` | Hashed token lineage | UUID, session_id, token_hash unique, parent_token_id, expiry/use/revocation | Session; parent must belong to same session |
| `contact_message` | Contact submissions | UUID, name/email/subject/message, status, assignee, resolution | Optional assigned user |
| `audit_event` | Application events | actor, event/entity type/ID, JSON data, warehouse IDs, occurred_at | Actor and relevant warehouse scopes |

Database checks enforce nonnegative stock/cost, reserved ≤ stock, positive quantities, and received ≤ ordered. NUMERIC(19,4) quantities/costs map to BigDecimal. API timestamps are ISO-8601 instants.

## Flyway and business history

| Migration | Change |
| --- | --- |
| V1 `baseline_schema` | Creates the 23 tables, keys, checks, indexes and pgcrypto extension |
| V2 `security_roles_permissions` | Five roles, initial 20 permissions and mappings |
| V3 `stock_movement_db_audit` | Actor lookup and AFTER INSERT/UPDATE/DELETE movement-header snapshots |
| V4 `operational_integrity` | Operational permissions, transfer/compensation/purchase links, ledger immutability/posting guards, indexes and refresh child uniqueness |
| V5 `scoped_audit_events` | Warehouse references/backfill/index for scoped application-event reads |
| V6 `ledger_relationship_guards` | Deferred transfer-pair validation, exact compensation/purchase line matching, refresh lineage/hash checks and one product per purchase |
| V7 `create_replenishment_policy` | Creates existing replenishment settings storage and product/warehouse constraints |
| V8 `reconcile_replenishment_policy_table_name` | Conditionally renames that table to match the existing JPA entity without deleting data |

Latest schema version: **8**. Applied migrations must never be edited; add V9 or later for evolution. Flyway clean is disabled.

`flyway_schema_history` tracks deployed database versions; movements/lines track business stock changes; `stock_movement_audit` captures header OLD/NEW snapshots; `audit_event` records application actions.

## Business flows

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

