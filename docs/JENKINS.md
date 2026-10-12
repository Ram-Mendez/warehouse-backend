# Jenkins: from Git to a local image

Status: **pipeline implemented; execution on a Jenkins controller has not been verified**.
The Maven and Docker commands are independently executable locally. No registry push,
deployment credentials or deployment environment are configured.

```text
Git commit -> Jenkins checkout -> Java/Docker check -> Maven clean verify
                                                      | compile
                                                      | tests (PostgreSQL containers)
                                                      | executable JAR
                                                      v
                                             local Docker image
                                                      |
                                             future deployment
```

## What each stage does

1. **Checkout** downloads the exact revision selected by the Jenkins SCM job.
2. **Verify Java and Docker** rejects a JDK other than 21 and an unavailable daemon.
   The repository's Maven Wrapper selects Maven; Jenkins does not silently choose a
   different global installation.
3. **Maven clean verify: tests and JAR** removes old outputs, compiles, runs tests,
   packages the executable Spring Boot JAR and completes Maven verification.
   `verify` includes `test` and `package`; repeating all three commands would run
   the same tests again without adding coverage. Integration tests end in `Test`
   and run through Surefire in the test phase, not Failsafe.
4. **Build local Docker image** creates `warehouse-backend:${BUILD_NUMBER}` on the
   agent's daemon, only after Maven succeeds. It neither pushes nor deploys it.

`junit` publishes XML results even when tests fail. Missing reports also fail this
step; they are not accepted as a successful zero-test build. On success Jenkins
archives the executable JAR and its fingerprint. The shell propagates Maven's
nonzero exit code, so a failed test prevents the Docker stage.

## Agent setup

Create a Jenkins Pipeline from SCM (or Multibranch Pipeline), with script path
`Jenkinsfile`. Provide a **trusted Linux agent** labelled `linux-docker-java21` with:

- JDK 21 on `PATH`, Git, a POSIX shell and normal shell utilities.
- Docker CLI and access to a working Linux-container daemon.
- Network access to Maven Central and the image registry for Temurin,
  PostgreSQL and Testcontainers' support images.
- Jenkins Pipeline, Git and JUnit plugins; standard artifact archiving support.

No application DB password or JWT secret is needed for tests. Testcontainers
starts its own PostgreSQL 18 with isolated credentials; `application-test.yml`
contains a test-only signing key. It does not use `localhost:5434/warehouse`.
Do not disable Testcontainers cleanup or mount a developer database into tests.

Prefer a host/VM agent for this small project. Containerized Jenkins agents need
additional daemon socket and mapped-port routing configuration; copying a socket
mount alone does not prove that Testcontainers can reach PostgreSQL. Docker access
is privileged: do not run untrusted fork code on an agent with production access.

The pipeline serializes builds for the same job, limits execution to 20 minutes
and keeps ten build records. Local images remain on the agent; apply an agent
retention policy rather than running a broad Docker prune in this project's job.

## Why Docker packages again

The Dockerfile is a self-contained multi-stage build. Its JDK stage invokes the
same Maven Wrapper with `-DskipTests package`, then copies only the executable JAR
to a non-root JRE image. Docker build has no Docker daemon inside it for
Testcontainers. **Skipping tests in that packaging stage is intentional and is
not the CI verification result**: the preceding `clean verify` is mandatory.

This duplicates compilation but keeps `docker build .` usable from a clean
checkout. The CI JAR and image JAR come from the same checkout, but this pipeline
does not claim byte-for-byte artifact promotion or reproducible image digests.

## Run the same checks locally

```powershell
.\mvnw.cmd clean verify
docker build -t warehouse-backend:local .
```

On Linux: `./mvnw -B -ntp clean verify`. If tests fail, inspect
`target/surefire-reports`; do not add `-DskipTests` to Jenkins to make it green.

## Future deployment boundary

A deployment would need an actual destination, registry, managed secrets, TLS,
database backup/restore plan, migration rollout policy and an image promotion
strategy. None is invented here. A Docker image proves packaging; it does not
prove production operation, availability or delivery experience.

References: [Declarative Pipeline](https://www.jenkins.io/doc/book/pipeline/syntax/),
[JUnit reporting](https://www.jenkins.io/doc/pipeline/steps/junit/),
[Docker multi-stage builds](https://docs.docker.com/build/building/multi-stage/).
