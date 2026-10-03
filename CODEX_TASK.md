# CODEX_TASK.md

Implement Phase 1 completely; do not only scaffold or plan.

1. Security/authentication
   - Entities/repositories/services/controllers for security_user, roles, permissions, auth_session, auth_refresh_token.
   - POST /api/v1/auth/login
   - POST /api/v1/auth/refresh
   - POST /api/v1/auth/logout
   - JWT access token with roles + permissions claims.
   - 45-minute access token.
   - Opaque 48-byte refresh token, SHA-256 stored, one-time rotation.
   - Detect refresh reuse and revoke whole auth session.
   - @EnableMethodSecurity.

2. Warehouse authorization
   - Global permission + warehouse scope checks.
   - A user can have permission globally but still receive 403 for a warehouse outside their scope.

3. Core inventory
   - CRUD/read APIs for category, supplier, product, warehouse, warehouse_location.
   - Inventory read endpoint.
   - Movement creation and posting for RECEIPT, ISSUE, TRANSFER, ADJUSTMENT, RETURN.
   - Posting movement must update balances atomically and never allow negative stock.
   - Concurrent posts must not oversell stock.

4. Audit
   - Application audit_event for important actions.
   - Before stock_movement writes, set `SET LOCAL app.current_user_id = '<authenticated id>'` through a transaction-scoped JDBC call.
   - Prove V3 trigger captures INSERT and UPDATE with actor_user_id.
   - Provide GET /api/v1/audit/movements/{movementId} protected by PERM_AUDIT_READ.

5. Contact
   - Public POST /api/v1/contact.
   - Protected support endpoints for reading/changing status.

6. Tests
   - Add Testcontainers PostgreSQL integration tests.
   - Test refresh r1->r2->r3 and old-token rejection.
   - Test reused old refresh revokes session.
   - Test permission present but warehouse scope missing => 403.
   - Test two concurrent ISSUE movements competing for insufficient stock: exactly one succeeds.
   - Test TRANSFER rolls back completely if target update fails.
   - Test DB movement audit trigger contains INSERT/UPDATE snapshots and actor id.

Do not weaken constraints to make tests pass. Keep all tests green.
