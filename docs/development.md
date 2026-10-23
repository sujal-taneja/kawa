# Development

## Project layout

```
kawa/
├── pom.xml          parent pom: shared settings and the module list
├── kawa-server/     the service: HTTP API, claim and lease logic, reaper, migrations
├── kawa-client/     the Java client library and KawaWorker
├── examples/        DemoWorker.java, a dependency-free worker
├── docs/            these documents
└── compose.yaml     Kawa + PostgreSQL
```

## Building and testing

```bash
./mvnw verify
```

This builds both modules and runs every test. The server's tests use a real PostgreSQL started by [Testcontainers](https://testcontainers.com), so Docker must be running. The tests cover:

- every state transition, lease-token check and the reaper;
- the HTTP API, including problem-details errors and the OpenAPI spec;
- idempotent enqueue, including 20 concurrent retries that must create one job;
- delayed jobs and priorities;
- `ConcurrentTakeTest`: 10 workers released at once against 1,000 jobs, each job claimed exactly once. Removing `FOR UPDATE SKIP LOCKED` from the claim query makes it fail;
- the Java client and `KawaWorker`, against a scripted HTTP server and end to end against the real server.

## Running from source

Needs Java 21.

```bash
docker compose up -d postgres
./mvnw -pl kawa-server -am package -DskipTests
java -jar kawa-server/target/kawa-server-*.jar
```

`-am` also builds `kawa-client`, which the server's tests depend on.

## Docker image

```bash
docker build -f kawa-server/Dockerfile -t kawa .
```

The build context is the repository root, because the server needs the parent pom. The image builds in two stages: a JDK stage compiles the jar, then a JRE-only stage runs it as a non-root user. `docker compose up -d --build` rebuilds the image after code changes.

## Configuration

Settings live in [`application.yml`](../kawa-server/src/main/resources/application.yml). Each can be overridden with an environment variable (`SPRING_DATASOURCE_URL`) or a flag (`--server.port=9090`).

| Property | Default | Meaning |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/kawa` | PostgreSQL connection |
| `spring.datasource.username` / `password` | `kawa` / `kawa` | Local development only |
| `server.port` | `8080` | HTTP port |
| `kawa.reaper.interval-ms` | `5000` | How often expired leases are reclaimed |

## Database migrations

[Flyway](https://flywaydb.org) applies `kawa-server/src/main/resources/db/migration/V*.sql` on startup and records them in `flyway_schema_history`. A migration that has been applied must never be edited; changes go in a new, higher-numbered file.

## Colima

Testcontainers needs two settings to work with Colima:

```bash
echo "docker.host=unix://$HOME/.colima/default/docker.sock" >> ~/.testcontainers.properties
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock   # add this to your shell profile
```

## Continuous integration

GitHub Actions runs `./mvnw -B verify` on every push to `main` and on every pull request, with read-only permissions and a 15-minute timeout. Dependabot opens weekly update pull requests for Maven dependencies and GitHub Actions.
