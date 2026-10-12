# Backend interview map

Read the code, run the cited test, then explain the guarantee in your own words.
**IMPLEMENTED** means executable code exists. **PARTIALLY IMPLEMENTED** identifies
a concrete boundary. **NOT IMPLEMENTED** means do not claim it from this repository.
Implementation here is project evidence, not evidence of professional production experience.

Java paths below are relative to `src/main/java/com/rammendez/warehouse/`.
Test classes are in `src/test/java/com/rammendez/warehouse/`.

## Spring Boot request flow — IMPLEMENTED

**Files/classes:** [WarehouseBackendApplication](../src/main/java/com/rammendez/warehouse/WarehouseBackendApplication.java),
[ProductController](../src/main/java/com/rammendez/warehouse/product/ProductController.java),
[ProductService](../src/main/java/com/rammendez/warehouse/product/ProductService.java),
[ProductRepository](../src/main/java/com/rammendez/warehouse/product/ProductRepository.java).

**What happens:** Boot creates the application context and embedded HTTP server.
Filters establish correlation and authentication, MVC validates a DTO, a service
authorizes and executes the operation, and the repository accesses PostgreSQL.
Constructor injection makes each dependency visible.

**Why / failure prevented:** HTTP mapping stays separate from business invariants;
other callers cannot bypass service-level authorization merely by skipping a controller.

**20-second answer:** “My controllers translate HTTP into validated DTOs. Services
define authorization and transaction boundaries, and repositories persist. Spring
injects these dependencies through constructors, so each responsibility is visible.”

## REST, DTOs and status codes — IMPLEMENTED

**Files/classes:** `product/ProductController.java`, `product/ProductDtos.java`,
`purchase/PurchaseController.java`, `common/PageResponse.java`.

**What happens:** POST creation returns 201; reads/updates return DTOs. Validation
uses 400, absent resources 404 and state/version conflicts 409. Lists use bounded
page/size and explicit sort order. Purchase transitions have named command routes.

**Why / failure prevented:** Entities and credential fields do not become accidental
JSON contracts. Page limits bound work; stable ordering avoids arbitrary page contents.
Offset pagination can still shift under concurrent inserts and deep offsets cost more.

**20-second answer:** “I expose DTOs, meaningful HTTP statuses and bounded pages.
Business conflicts are 409, not generic 500s. Pagination is explicitly ordered,
although it is not a snapshot across multiple requests.”

## Validation — IMPLEMENTED

**Files/classes:** `movement/MovementDtos.java`, `security/AdminDtos.java`,
`product/ProductService.java`; `OperationsIntegrationTest`.

**What happens:** Bean Validation checks structure, ranges and decimal precision
before service calls. Services check references and workflow state. Scope list
elements have both `@NotNull` and `@Valid`: cascading alone ignores null.

**Why / failure prevented:** Malformed scope arrays return 400 without deleting
existing access. Positive quantities and bounded precision prevent invalid writes.
Database constraints remain a final guard against other writers and races.

**20-second answer:** “I validate syntax at the API boundary and business rules in
the service. The database still enforces integrity. Each layer catches a different
failure; Java validation alone cannot protect against concurrent or direct SQL writes.”

## Global errors / ProblemDetail — IMPLEMENTED

**Files/classes:** [ApiExceptionHandler](../src/main/java/com/rammendez/warehouse/common/ApiExceptionHandler.java),
`common/BusinessException.java`, `config/SecurityConfig.java`; `OperationsIntegrationTest`.

**What happens:** MVC errors and security filter failures share ProblemDetail
status/title/detail/instance plus timestamp and correlation ID. Security failures
need their own entry point because they occur before controller advice. Resource-server
401 responses also return a Bearer challenge. Unexpected failures have safe generic details.

**Why / failure prevented:** Clients can handle failures consistently without seeing
SQL, exception messages, credentials or stack traces.

**20-second answer:** “I use ProblemDetail for a predictable error contract. MVC
advice handles application errors and the security entry point handles filter
errors. Both include the request ID, but neither exposes internal exceptions.”

## Transactions and rollback — IMPLEMENTED

**Files/classes:** [MovementService](../src/main/java/com/rammendez/warehouse/movement/MovementService.java),
[PurchaseService](../src/main/java/com/rammendez/warehouse/purchase/PurchaseService.java),
`audit/AuditService.java`; `InventoryMovementIntegrationTest`, `PurchaseContactAdminIntegrationTest`.

**What happens:** Public service methods start transactions. Purchase receipt calls
a separate movement service whose default REQUIRED propagation joins that transaction.
Balances, movement headers/lines, trigger audit and application audit commit together.
Runtime exceptions roll back; checked exceptions do not automatically do so by default.

**Why / failure prevented:** No stock increase without its history, and no received
purchase if posting fails. REQUIRES_NEW here would permit partial commits.
Proxy-based annotations require a call through the Spring proxy; self-invocation
does not start a new annotated boundary.

**20-second answer:** “A transaction represents the whole business operation.
Purchase receipt and movement posting share one transaction, so stock, order state
and audits either all commit or all roll back. I test failure paths, not just success.”

## Read-only transactions and flush — IMPLEMENTED

**Files/classes:** `product/ProductService.java`, `product/ProductRepository.java`.

**What happens:** Service reads default to `@Transactional(readOnly = true)`;
write methods override it. `saveAndFlush`/`flush` synchronize product changes before
dependent JPA native upserts and before returning the new version.

**Why / failure prevented:** The response reports the actual Hibernate version and
constraints fail inside the operation. Flush sends SQL; it does **not** commit.
Read-only expresses intent and may enable optimizations; it is not authorization
or a repeatable snapshot under the default READ COMMITTED isolation.

**20-second answer:** “Flush and commit are different. I flush a product to obtain
its current version and detect conflicts, while supplier links and audit still
belong to the same transaction. Read-only marks queries, not access permissions.”

## JPA/Hibernate and optimistic locking — IMPLEMENTED

**Files/classes:** [Product](../src/main/java/com/rammendez/warehouse/product/Product.java),
`product/ProductService.java`; `CatalogSecurityIntegrationTest`.

**What happens:** Product is a managed entity. Hibernate tracks dirty fields and
uses `@Version` in updates. The client must submit its last observed version.
Supplier-only edits also make the product dirty so its version advances.

**Why / failure prevented:** A stale editor cannot silently overwrite newer data.
The HTTP version comparison handles already-stale input; Hibernate handles a
concurrent commit between reading and updating. Conflicts become 409.

**20-second answer:** “Optimistic locking lets readers proceed without long-held
locks. Every product update includes its version; if somebody updated it first,
the stale update fails. The client must reload and reconcile instead of retrying blindly.”

## JPA associations and fetching — IMPLEMENTED; EntityGraph/Specifications — NOT IMPLEMENTED

**Files/classes:** `category/Category.java`, `warehouse/WarehouseLocation.java`,
`product/ProductSupplier.java`, `security/SecurityUser.java`, `product/ProductRepository.java`.

**What happens:** Lazy associations map category parents, warehouse locations,
product/supplier links, order and movement lines, auth sessions, roles and permissions.
Supplier-link pages explicitly fetch the supplier; movement-list lines are fetched
in one batch for each page. DTOs are built within the service transaction.
Open Session in View remains disabled in `application.yml`.

**Why / failure prevented:** Entity relationships express foreign keys without
exposing entities as REST contracts. Explicit fetches prevent per-row supplier
lookups, and proxy identifier getters avoid reading uninitialized fields.
EntityGraph and Specifications are not needed for the current queries.

**20-second answer:** “Persistence uses Spring Data JPA throughout. I keep associations
lazy and fetch what each use case needs before mapping to DTOs. Native queries stay
inside JPA for PostgreSQL-specific operations such as upserts and audit JSON.”

## Pessimistic locking, races and atomic transfers — IMPLEMENTED

**Files/classes:** [InventoryRepository](../src/main/java/com/rammendez/warehouse/inventory/InventoryRepository.java),
`movement/MovementService.java`, `category/CategoryRepository.java`; `InventoryMovementIntegrationTest`.

**What happens:** JPA `@Lock(PESSIMISTIC_WRITE)` serializes competing balance checks. A sorted
location/product key orders both balance creation and locking, including opposite
transfers. Available stock excludes reservations. Category hierarchy edits use a
transaction advisory lock to prevent concurrent cycle creation.

**Why / failure prevented:** Two decrements cannot both spend the same available
stock. Deterministic ordering prevents opposing transfers taking locks in reverse
order. Locks reduce concurrency; five-second lock timeouts bound waiting.

**20-second answer:** “For stock I lock before checking availability, then change
the balance within the same transaction. Multi-row operations lock in the same
order. Tests race competing requests and verify the final stock and complete history.”

## PostgreSQL, SQL, constraints and query shape — IMPLEMENTED

**Files/classes:** `product/ProductRepository.java`, `common/Sql.java`,
`movement/MovementRepository.java`, [V1 migration](../src/main/resources/db/migration/V1__baseline_schema.sql).

**What happens:** Parameters bind values; sort columns come from an allowlist.
LIKE escapes percent, underscore and backslash for literal searching. List queries
batch movement lines rather than fetching once per header. Foreign keys, unique
keys and CHECK constraints protect references, quantities and duplicate balances.
NUMERIC maps to BigDecimal; APIs use Instant/UTC for timestamps.

**Why / failure prevented:** Binding prevents value injection; allowlisting protects
SQL identifiers that cannot be bound. Escaping prevents accidental wildcard searches.
Indexes support keys/filter paths but cost writes/storage. There is no load or
EXPLAIN benchmark establishing a specific performance gain in this audit.

**20-second answer:** “I bind user values and allowlist dynamic sort columns. The
database enforces invariants even if application checks race. I batch list-related
data to avoid per-row queries, but I would measure plans before claiming performance.”

## Ledger immutability and audit actor — IMPLEMENTED

**Files/classes:** `audit/AuditService.java`, `audit/AuditRepository.java`,
`movement/MovementService.java`; V3, V4 and V6 in `src/main/resources/db/migration/`.

**What happens:** `set_config('app.current_user_id', ..., true)` puts the actor on
the transaction connection before movement writes. Triggers store OLD/NEW header
snapshots. Posted headers/lines cannot be changed; corrections append exact
compensations. Deferred constraints check transfer completeness at commit.

**Why / failure prevented:** A pooled connection cannot retain the previous actor
after commit/rollback. History cannot silently change; audit reads enforce scope
on both OLD and NEW snapshots. This is database history, not external tamper-proof storage.

**20-second answer:** “I keep the original posted movement and append a reversing
movement. PostgreSQL enforces immutability and records the actor in the same
transaction. Audit authorization covers historical as well as current warehouse scope.”

## Spring Security: identity versus authorization — IMPLEMENTED

**Files/classes:** [SecurityConfig](../src/main/java/com/rammendez/warehouse/config/SecurityConfig.java),
[AccessControl](../src/main/java/com/rammendez/warehouse/security/AccessControl.java),
`security/SecurityRepository.java`; `CatalogSecurityIntegrationTest`.

**What happens:** The filter chain authenticates Bearer tokens. Service method
security checks authorities and AccessControl checks resource scope. Authorities
come from current database roles/permissions, not blindly from token claims.
HTTP sessions are stateless; CSRF is disabled for explicit Bearer credentials,
not a cookie-based login. Passwords use BCrypt with a UTF-8 byte limit.

**Why / failure prevented:** Valid identity alone does not grant another warehouse's
data. DB-backed checks reflect disabled users and permission changes, at the cost
of database work per authenticated request.

**20-second answer:** “Authentication proves who calls. Authorization checks both
what operation they may perform and which warehouse they may access. Even an
admin has resource scope, and I test 401 separately from 403.”

## JWT claims, signing and expiry — IMPLEMENTED

**Files/classes:** `config/SecurityConfig.java`, [AuthService](../src/main/java/com/rammendez/warehouse/auth/AuthService.java),
`config/JwtProperties.java`; `AuthIntegrationTest`.

**What happens:** HS256 signs an access token with subject, issuer, issuance/expiry,
account version and roles/permissions. Decoder validation checks signature, issuer
and time bounds (including framework clock-skew allowance). Malformed account
versions are rejected with 401. Default access lifetime is 45 minutes.

**Why / failure prevented:** Unsigned/tampered, expired or foreign-issuer identity
is rejected. Signed does not mean encrypted: claims are readable and contain no
password or signing secret. Signing-key rotation is not implemented.

**20-second answer:** “A JWT is signed claims, not encrypted session data. I verify
its signature, issuer and expiry, then use its subject to load current permissions.
A valid token still has to pass resource authorization.”

## Refresh rotation and logout — IMPLEMENTED

**Files/classes:** `auth/AuthService.java`, `auth/AuthRepository.java`; `AuthIntegrationTest`.

**What happens:** 48 random bytes form an opaque refresh value; only its SHA-256
hash is stored. Session locking serializes rotation. Reuse revokes the whole family.
`TransactionTemplate` commits revocation before the service throws the 401 error.

**Why / failure prevented:** Throwing inside that transaction would roll back
revocation. Logout invalidates refresh tokens; access remains usable until expiry
unless account invalidation intervenes. Clients must serialize refresh requests.

**20-second answer:** “Refresh tokens rotate once and are hashed at rest. Reuse
revokes the family. I commit that revocation before returning the error so rollback
cannot undo the security decision. Logout does not pretend to erase issued JWTs.”

## Flyway and configuration ownership — IMPLEMENTED

**Files:** [application.yml](../src/main/resources/application.yml),
[migrations V1-V8](../src/main/resources/db/migration), [database reference](DATABASE.md).

**What happens:** V1 creates schema, V2 seeds roles/permissions, V3 adds movement
audit, and V4-V6 strengthen operational/ledger relationships. Flyway stores versions
and checksums in schema history; Hibernate only validates mappings.

**Why / failure prevented:** Append-only migration files keep environments on an
explainable evolution path. Editing deployed SQL breaks checksum consistency.
Flyway schema history is not the business movement history. No old migration was
edited just to add study comments; explanations live here and at configuration.

**20-second answer:** “Flyway versions database evolution. I add a new migration
instead of editing an applied one, and Hibernate validates rather than generating
schema. Stock movement history solves a separate business audit problem.”

## Testing, MockMvc and Testcontainers — IMPLEMENTED

**Files/classes:** [PostgresIntegrationTest](../src/test/java/com/rammendez/warehouse/PostgresIntegrationTest.java),
`DatabaseIntegrityIntegrationTest`, `InventoryMovementIntegrationTest`,
`OperationsIntegrationTest`, `config/JwtPropertiesTest`.

**What happens:** PostgreSQL 18 starts in Docker, real Flyway migrations run and
MockMvc exercises the security/controller/service/SQL stack. Tests commit real
transactions so deferred constraints, rollback and races remain visible. Fixtures
use unique IDs; test methods are not parallelized against shared fixture state.
Small configuration unit tests need no container.

**Why / failure prevented:** H2 or mocked repositories would miss PostgreSQL locks,
triggers and SQL behavior. A reproduced defect gets a regression at the boundary
that failed. Bounded latches and database lock observation make races meaningful.

**20-second answer:** “Unit tests check isolated logic; integration tests prove the
framework and database contract. I use real PostgreSQL because the important
failures involve locks, triggers and commits. A green mocked test would not prove those.”

## Correlation IDs and logging — IMPLEMENTED

**Files/classes:** [CorrelationFilter](../src/main/java/com/rammendez/warehouse/common/CorrelationFilter.java),
`common/ApiExceptionHandler.java`, `application.yml`; `OperationsIntegrationTest`.

**What happens:** A safe 1-64 character request ID is accepted or generated before
security, echoed and stored in MDC. Log pattern and error JSON include it.
Finally removes it, including after failures.

**Why / failure prevented:** Reused servlet threads cannot misattribute the next
request. Restricted characters prevent client-controlled log formatting. Logs
deliberately omit request bodies and exception messages that may contain secrets.
No distributed trace propagation or centralized collector is implemented.

**20-second answer:** “The response ID lets me connect an HTTP error to its log
entry. I validate incoming IDs and clear MDC in finally because servlet threads
are reused. This is request correlation, not full distributed tracing.”

## Typed configuration and secrets — IMPLEMENTED

**Files/classes:** [JwtProperties](../src/main/java/com/rammendez/warehouse/config/JwtProperties.java),
`application.yml`, `application-dev.yml`; `config/JwtPropertiesTest`.

**What happens:** Constructor-bound properties validate issuer, key byte length and
positive durations once at startup. Its toString is redacted. Default profile
requires environment-supplied DB credentials and key; dev/test profiles use fixtures.

**Why / failure prevented:** Bad lifetimes or weak/missing keys fail early, and
configuration logging cannot print the record's signing key. Environment variables
are an input mechanism, not a full secret manager or key-rotation solution.

**20-second answer:** “I group related settings in an immutable configuration
record and reject invalid values at startup. Secrets are supplied outside the
image and redacted; the development fallback is restricted to the dev profile.”

## Actuator and graceful shutdown — IMPLEMENTED

**Files/classes:** `application.yml`, `config/SecurityConfig.java`,
`OperationsIntegrationTest`, [Dockerfile](../Dockerfile).

**What happens:** Only health/info are web-exposed. Health hides diagnostic details;
info requires audit permission. Liveness tracks application availability; readiness
also checks DB. SIGTERM stops accepting work and permits a 30-second shutdown phase.
Container stop timeout should exceed that window.

**Why / failure prevented:** A DB outage should make the instance unready, not cause
an endless restart loop. Health does not disclose disk/DB details. The readiness
test covers application refusal, not a real DB outage; no in-flight load/drain
benchmark is claimed from the shutdown setting alone.

**20-second answer:** “Liveness answers whether restarting the process may help;
readiness answers whether it can serve traffic now. I include DB in readiness,
hide diagnostics and leave time for active requests when the process receives SIGTERM.”

## OpenAPI — IMPLEMENTED

**Files/classes:** [OpenApiConfig](../src/main/java/com/rammendez/warehouse/config/OpenApiConfig.java),
`application.yml`; `OpenApiContractIntegrationTest`.

**What happens:** Generated documentation supplies metadata, Bearer auth, operation
permissions and common errors. Fully qualified schema names disambiguate nested
DTOs such as ProductDtos.Input and WarehouseDtos.Input. The test resolves request
schemas and references across controller operations.

**Why / failure prevented:** Swagger must describe the body actually accepted by
the endpoint, not another identically named DTO. Documentation is not enforcement;
authorization and validation remain in running code.

**20-second answer:** “I test the generated OpenAPI contract, especially DTO schema
references, so Swagger examples stay usable. The Bearer scheme documents how to
authenticate, but real permission checks still run in Spring Security.”

## Docker packaging — IMPLEMENTED

**Files:** [Dockerfile](../Dockerfile), [.dockerignore](../.dockerignore), [compose.yml](../compose.yml).

**What happens:** A Java 21 JDK stage uses the Maven Wrapper to package; a JRE stage
contains the executable JAR and runs without root. Exec-form ENTRYPOINT forwards
SIGTERM to Java. Compose is only the development database.

**Why / failure prevented:** Build tools/source are not required in the runtime;
secrets and local artifacts stay outside the build context. Docker packaging skips
tests intentionally because Testcontainers needs a daemon; host/CI clean verify is
the gate. Mutable image tags mean builds are not claimed byte-reproducible.

**20-second answer:** “My multi-stage build separates compilation from runtime.
The image runs the JAR as a non-root user and receives secrets at startup. CI runs
the full PostgreSQL tests before image creation; building an image alone is not testing.”

## Jenkins / CI — PARTIALLY IMPLEMENTED

**Files:** [Jenkinsfile](../Jenkinsfile), [JENKINS.md](JENKINS.md).

**What happens:** A declarative pipeline checks out SCM, checks Java/Docker, runs
clean verify, publishes tests/archives the JAR, and builds a local image. The file
exists; execution on a configured Jenkins controller has not been verified.

**Why / failure prevented:** Test failures stop packaging from being treated as a
successful delivery. The Jenkins agent needs actual Docker access for Testcontainers.
No registry credentials, push, deployment or rollback system is invented.

**20-second answer:** “I have a Jenkins pipeline definition that makes a green
Maven verification the prerequisite for building an image. I can explain its agent
requirements, but I would not claim an operated deployment pipeline from this file alone.”

## Idempotency, scheduled work and external side effects — PARTIALLY IMPLEMENTED

**Files/classes:** `config/DevDataInitializer.java`, `purchase/PurchaseService.java`,
`movement/MovementService.java`; `DevInitializationIntegrationTest`.

**What happens:** Dev seeding tolerates repeated starts; purchase state and locks
prevent duplicate receipt. Manual stock commands have no idempotency key and
repeating one creates another movement. There is no `@Scheduled` work, reservation
expiry processor, outbound messaging or email side effect.

**Why / failure prevented:** Startup must not reset accounts or grant revoked
permissions. Purchase locking prevents a second receipt, but this is not generic
network-retry idempotency. No after-commit/outbox guarantee is claimed.

**20-second answer:** “I distinguish repeat-safe seeding and state-guarded purchase
receipt from generic request idempotency. This project does not yet make manual
stock retries safe, nor does it have scheduled or external-message processing.”

## Features deliberately absent — NOT IMPLEMENTED

Kafka/RabbitMQ, Redis caching, async business processing, microservices, Kubernetes,
distributed tracing, production TLS/deployment, backup-restore exercises, signing-key
rotation and load benchmarks are absent. Reservations have storage and affect
available stock, but no management API. Purchases support full receipts only.

**20-second answer:** “I chose features that protect this application's actual
invariants. I would introduce a broker, cache or scheduler only for a specific
requirement and then test its failure modes. I do not claim them because a table,
dependency or document mentions the idea.”
