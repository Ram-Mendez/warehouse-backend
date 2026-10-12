# Baseline and engineering changes

Audit date: 2026-10-08. Starting branch: `training/warehouse-breakage-2`.
Two locally modified test files existed before this work:
`PurchaseContactAdminIntegrationTest` and `TrainingBatchTwoIntegrationTest`.
Their changes were preserved. No commit, merge or push was requested.

## BASELINE (before production changes)

| Area | Evidence |
| --- | --- |
| Build | Java 21.0.10, Spring Boot 4.1.1; `mvnw.cmd test` succeeded; no compiler warnings |
| Tests | 68 passed, 0 failures, 0 errors, 0 skipped; 1m25s in this environment |
| Database | Docker 29.3.1 available; Compose PostgreSQL healthy at localhost:5434; tests use separate PostgreSQL 18.6 Testcontainers |
| Flyway | All six migrations applied/validated; Hibernate validates mappings |
| Security | DB permissions/scopes, BCrypt, signed JWTs, refresh rotation/reuse/logout already implemented and tested |
| Docker | Local PostgreSQL Compose present; backend Dockerfile absent |
| Jenkins | No Jenkinsfile or Jenkins documentation |
| Operations | Actuator, ProblemDetail and correlation filter already present; operational HTTP contracts lacked dedicated tests |
| Documentation | README contained old test totals; no interview map |

Inspected Maven/configuration, all feature packages, controllers/DTOs, repositories,
services, migrations, tests, Compose and project instructions. Independent scoped
security and persistence reviews supplemented the inspection. No concrete stock,
ledger, SQL-mapping or N+1 defect was established; no performance improvement is
claimed. Framework notices about enabled Swagger and unavailable JTA are not
compiler failures; this application uses local database transactions, not JTA.

## Meaningful fixes: problem -> cause -> fix -> verification

| Problem | Root cause | Fix | Regression evidence |
| --- | --- | --- | --- |
| PUT user scopes with `{"scopes":[null]}` could return 500 | Cascaded `@Valid` ignores null; service dereferences each element | Container element `@NotNull` returns 400 before mutation | `OperationsIntegrationTest.nullScopeElementIsBadRequestAndPreservesExistingWarehouseAccess` checks response and retained access |
| An ordinary authenticated caller could inspect DB/disk health details | `when_authorized` with no health roles treats any authenticated principal as authorized | Health exposes status and public probe names without diagnostics; only health/info are web-exposed | Anonymous, worker and admin checks; info 401/403/200; sensitive endpoints unavailable |
| A correctly signed token with string `av` could fail inside the security filter | Unchecked cast to `Number` before rejection | Type/integrality validation rejects invalid claims with 401 | `AuthIntegrationTest.signedTokensWithMalformedAuthVersionOrMissingSubjectAreUnauthorized` exercises signed malformed claims |
| Resource-server 401 lacked a Bearer challenge | Custom ProblemDetail entry point wrote only status/body | Add `WWW-Authenticate: Bearer` while retaining ProblemDetail | Existing malformed/missing JWT test strengthened |
| Invalid configured TTL could issue immediately unusable tokens | Scattered `@Value` values had no positive-duration validation | Immutable `JwtProperties` validates before startup; secret is redacted | Binding/validation unit tests and normal auth integration tests |

Business contracts remain the same: full purchase receipt, manual stock commands,
compensating movements and current DB permissions. The intentional HTTP changes
are malformed scope payload 400, malformed signed claims 401, Bearer challenge,
and reduced operational detail exposure. No Flyway SQL was changed.

## Useful additions

- Multi-stage Java 21 image with a non-root runtime; secrets supplied at runtime.
- Declarative Jenkins test/build pipeline; no invented registry or deployment.
- Explicit readiness (including DB) and liveness, plus graceful shutdown settings.
- Dedicated HTTP coverage for operational access, ProblemDetail, correlation IDs
  and MDC cleanup, with a test-only failing endpoint to verify safe 500 responses.
- Short comments at transaction, locking, JWT, SQL and test boundaries.
- Practical README and linked database/API/CI/interview documentation.

## Verification boundaries

The first focused run had 22 tests, with one failed test assertion and no errors
or skips: root health has a public `groups` field in addition to `status`.
The assertion was corrected to permit those probe names while still rejecting
diagnostic details/components. The operations rerun passed all 7 tests.

Final `mvnw.cmd clean verify`: **79 passed, 0 failures, 0 errors, 0 skipped**
(1m42s). Surefire XML totals were counted independently. The executable JAR was
packaged successfully; no Maven compiler warnings were reported.

`docker build -t warehouse-backend:local .` passed. The rebuilt image started with
the default profile, runtime-injected signing secret and existing Compose database.
Seven live HTTP checks passed: health, liveness, readiness, protected info/products
401 contracts, OpenAPI (58 operations and Bearer scheme) and Swagger UI. Runtime
UID/GID were 10001. SIGTERM logged graceful shutdown completion and closed the
connection pool; exit 143 reflects the termination signal. The temporary smoke
container was removed; the development PostgreSQL container remains running.

A Docker packaging build intentionally skips tests inside the build stage;
the full host/CI `clean verify` is the test gate. Jenkins controller execution is
not claimed without a configured controller/agent. Graceful shutdown is configured
and container SIGTERM can exercise it; no load/drain benchmark is claimed.

PowerShell initially classified Docker's normal stderr progress as a
`NativeCommandError` while the image export completed. A repeat using direct
process capture confirmed Docker's actual exit code **0**; see the local ignored
`docker-build-verified.log`. This was shell capture behavior, not a suppressed
image-build failure.
