# Warehouse Backend

## What it demonstrates

A warehouse API for catalogs, stock receipts/issues/transfers, purchases, immutable movement history, authentication, scoped access, user administration, and contact submissions. Swagger is the UI; there is no frontend.

## Stack

Java **21**, Spring Boot **4.1.1**, Maven Wrapper **3.9.16**, PostgreSQL **18** (local container verified as **18.6**), Spring Data JPA/Hibernate, Spring Security OAuth2 Resource Server/JOSE, Flyway, Jakarta Validation, Actuator, springdoc OpenAPI **3.1.1**, and PostgreSQL Testcontainers. Maven manages the Spring, Hibernate, Flyway, and Testcontainers versions.

## Quick start

Install JDK 21 and Docker Desktop with Linux containers. Set `JAVA_HOME`; the first build needs internet. In PowerShell:

```powershell
Set-Location C:\Users\balar\Desktop\warehouse-backend
java -version
.\mvnw.cmd -version
docker compose up -d --wait postgres
docker exec warehouse-postgres pg_isready -U warehouse -d warehouse
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

Once startup finishes, open another PowerShell window:

```powershell
Start-Process 'http://localhost:8080/swagger-ui/index.html'
Invoke-RestMethod 'http://localhost:8080/actuator/health'
```

Stop the backend with `Ctrl+C`. Stop the database with `docker compose stop postgres`; its volume retains data. Tests create a separate PostgreSQL container and never use the development database.

## Database

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

Connections use a 5-second lock timeout and 30-second statement timeout. Health/probes are public without diagnostic details; `/actuator/info` requires `PERM_AUDIT_READ`. Metrics are not web-exposed. Every response carries `X-Correlation-Id`; safe incoming IDs are retained and also included in errors/log context.

## Development fixtures

**THESE ARE DEVELOPMENT CREDENTIALS ONLY.** They exist only when the `dev` profile runs.

| Username | Password | Role | Warehouse access | Purpose |
| --- | --- | --- | --- | --- |
| `admin` | `Admin-local-2026!` | `ROLE_ADMIN` | North and South | Catalog, operations, security and support administration |
| `manager` | `Manager-local-2026!` | `ROLE_MANAGER` | North and South | Operational management, purchases and audit |
| `worker` | `Worker-local-2026!` | `ROLE_OPERATOR` | North only | Scoped stock operations and reads |

Seeder also creates `WH-NORTH`, `WH-SOUTH`, a `DEFAULT` location in each, categories `PACKAGING` and `TOOLS`, suppliers `SUP-LOCAL` and `SUP-GLOBAL`, and products `BOX-001`, `TAPE-001`, `TOOL-001`. Supplier unit costs are in `product_supplier`, not the product table. Initial balances are zero.

Restarts preserve existing records, passwords, roles and scopes. Discover IDs through GET. A new warehouse gets a DEFAULT location and its creator gets MANAGER scope; other accounts need explicit assignments.

## Security

1. Login verifies the account/BCrypt password and creates a session.
2. The HS256 access JWT lasts **45 minutes**: send `Authorization: Bearer <accessToken>`.
3. Refresh tokens contain **48 secure random bytes**, expire after seven days, and are stored only as SHA-256 hashes.
4. Refresh consumes the current token and returns a replacement: `r1 → r2 → r3`. Each session is locked during rotation.
5. Reuse revokes the entire refresh family before returning 401. Avoid concurrent refresh retries.
6. Logout revokes the session/family; access JWTs naturally expire.

Roles grant database permissions. Every JWT request reloads account state/authorities. Disabled/locked accounts fail; account/role edits advance `auth_version` and revoke refresh families. Passwords are never returned; BCrypt accepts at most 72 UTF-8 bytes.

Permission and warehouse scope are separate checks, including for admins. VIEWER scopes allow reads; OPERATOR/APPROVER/MANAGER allow permitted writes. Transfers/history require both warehouse scopes; audits check all warehouses in OLD and NEW snapshots.

V2 seeds ADMIN/MANAGER/OPERATOR/AUDITOR/SUPPORT; V4 adds six permissions. ADMIN has all 26; MANAGER excludes user/contact administration. 401 means unauthenticated; 403 means permission/scope denied. Public login/refresh/contact ignore stale bearer headers.

## Database reference and API examples

See [database tables, migrations and transaction flows](docs/DATABASE.md) and
[Swagger examples and endpoint map](docs/API.md). Applied Flyway V1-V8 migrations
are immutable; add a new version for schema changes.

## Architecture

Request flow: HTTP -> correlation filter -> security chain -> controller/DTO validation
-> service (authorization/transaction) -> repository -> PostgreSQL -> DTO or ProblemDetail.

Under `src/main/java/com/rammendez/warehouse`:

| Package | Responsibility |
| --- | --- |
| `auth` | Login, JWT issuance, refresh families/rotation and logout |
| `security` | Account/role/permission persistence, scopes, password policy and admin APIs |
| `category`, `supplier`, `product` | Catalog services, validation and persistence |
| `warehouse` | Warehouses and stock locations |
| `inventory` | JPA balance queries, pessimistic locks and atomic JPQL quantity updates |
| `movement` | Transactional posting, transfer and compensation ledger |
| `purchase` | Order lifecycle and full receipt |
| `contact` | Public submissions and protected support management |
| `audit` | Transaction actor, application events and scoped audit reads |
| `common` | Errors, pagination, literal LIKE patterns and correlation filter |
| `config` | Security chain/JWT beans, OpenAPI and dev-only initializer |

Controllers handle HTTP/validation; services own authorization/transactions; repositories persist. All application persistence uses Spring Data JPA/Hibernate in the same Spring-managed transactions. Native PostgreSQL operations use JPA queries or EntityManager; no application JdbcTemplate or direct JDBC implementation remains. APIs return DTOs/projections. Migrations: `src/main/resources/db/migration`; tests: `src/test/java`; `.codex`: agent instructions. See [the persistence migration report](docs/JPA_PERSISTENCE_MIGRATION.md) for entities, repositories, concurrency guarantees and validation.

## Testing

```powershell
.\mvnw.cmd clean verify
```

Docker must run. Tests create clean PostgreSQL 18, migrate and validate JPA, then exercise real HTTP/security/service/SQL behavior: auth/refresh/logout, permission/scope denial, catalog CRUD/literal search/versioning, stock races/transfers/rollback, audit actor/snapshots/immutability/compensation, purchases/double receipt, contacts, admin replacement races and idempotent seeding. Latches, observed database locks and bounded waits coordinate races. No H2.

Surefire executes unit and integration tests in the test phase. Reports are in `target/surefire-reports`; missing Docker fails the run rather than silently skipping. New operational tests exercise safe health exposure, ProblemDetail, correlation IDs/MDC cleanup and invalid scope payloads. See [baseline and change evidence](docs/BASELINE_AND_CHANGES.md).

## Troubleshooting

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

## Transactions and concurrency

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


## Docker

Compose intentionally runs only the local PostgreSQL database. Build the backend:

```powershell
docker build -t warehouse-backend:local .
# Local demo: uses the existing Compose database and development fixtures.
docker run --rm --name warehouse-backend-local -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev -e DB_URL=jdbc:postgresql://host.docker.internal:5434/warehouse warehouse-backend:local
```

On Linux add `--add-host=host.docker.internal:host-gateway`. For non-dev startup,
supply DB_URL, DB_USERNAME, DB_PASSWORD and JWT_SECRET at runtime through your
secret mechanism. The JDK build stage packages; the JRE runs as UID 10001.
No runtime secrets are baked into the image.

The image packaging stage intentionally skips tests: **run clean verify first**.
Jenkins enforces this gate. Image creation alone does not prove tests passed.
Use `docker stop --timeout 40 warehouse-backend-local` to allow Spring's 30-second
shutdown window. Java 21 image tags can receive updates; reproducible digests
are not claimed.

## Jenkins

[Jenkinsfile](Jenkinsfile): checkout -> Java 21/Docker check -> Maven clean verify
(tests and executable JAR) -> local image tagged with the build number.
Test failures stop the pipeline. XML reports and successful JARs are retained.
[docs/JENKINS.md](docs/JENKINS.md) explains the agent and each stage.
Controller execution, registry push and deployment are not claimed.

## Operations

| Endpoint | Exposure |
| --- | --- |
| /actuator/health | Aggregate status and public probe names; no diagnostics |
| /actuator/health/liveness | Process availability; excludes DB |
| /actuator/health/readiness | Readiness state plus DB connectivity |
| /actuator/info | Static application metadata; audit permission required |

Other management endpoints, including metrics/env/heapdump, are not web-exposed.
Readiness returns 503 when unavailable. Liveness excludes DB to avoid restart
loops during a database outage.

A bounded X-Correlation-ID is accepted or generated, echoed, put in MDC and
included in logs/ProblemDetail. MDC is cleared after each request. This is request
correlation, not distributed tracing. Unexpected errors return safe 500 details;
logs retain exception class and request ID, not exception messages or bodies.

JwtProperties binds and validates issuer, secret and positive token lifetimes at
startup. Ordinary settings and runtime secrets retain their existing external
names. SIGTERM triggers graceful shutdown; no public shutdown endpoint exists.
Swagger can be disabled with SPRINGDOC_API_DOCS_ENABLED=false and
SPRINGDOC_SWAGGER_UI_ENABLED=false. No centralized log collector is configured.

## Interview map

[docs/BACKEND_INTERVIEW_MAP.md](docs/BACKEND_INTERVIEW_MAP.md) links exact code,
guarantees, prevented failures and 20-second interview answers.

No reservation API/expiry scheduler, partial receipts, generic stock-command
idempotency key, load benchmark, deployment/TLS or backup-restore exercise is
implemented. JPA associations cover categories, locations, supplier links and security; no EntityGraph or Specifications examples are implemented. This repository demonstrates implementation and tests; it does
not establish production operating experience.
