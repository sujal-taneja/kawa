# Changelog

All notable changes to Kawa are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org).

## [0.2.0]

### Added

- **Idempotent enqueue.** Send an `Idempotency-Key` header and retrying the request never creates a second job, even when retries race. A replay returns the original job with `200` and `Idempotent-Replayed: true`; the same key with a different payload is rejected with `422`.
- **Delayed jobs.** Run a job after `delaySeconds` or at an exact `runAt` instant, up to one year ahead.
- **Priorities** from 0 to 9. `take` serves the highest priority first, then the oldest.
- **Java client** (`kawa-client`, Java 17+): `KawaClient` for enqueueing and settling jobs, `EnqueueOptions`, and typed exceptions for every error the API can return.
- **`KawaWorker`**, a ready-made worker loop: your code runs for every job, returning acks it and throwing retries it, and `stop()` finishes the running job before exiting.
- **OpenAPI and Swagger UI.** The spec is served at `/v3/api-docs` and an interactive reference at `/swagger-ui/index.html`.
- **Docker image.** `docker compose up -d` runs Kawa with PostgreSQL. Release images are published to `ghcr.io/sujal-taneja/kawa` for `linux/amd64` and `linux/arm64`.
- **Java quickstart example** (`examples/java-quickstart`): a producer and workers you can run against a local Kawa.
- Guides for the HTTP API, the Java client, the architecture and development under `docs/`.

### Changed

- Errors are returned as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details (`application/problem+json`) with a `title`, a specific `detail` and the `jobId` where relevant. Clients that parsed the previous error body need updating.
- The repository is now a multi-module Maven build. The server's artifact is `kawa-server` (previously `kawa`).

## [0.1.0]

### Added

- A job queue on PostgreSQL with an HTTP API under `/api/v1`: enqueue, take, extend, ack, nack and get.
- Lease-based claiming with `FOR UPDATE SKIP LOCKED`, so concurrent workers never claim the same job.
- Lease tokens that stop a stalled worker from settling a job it no longer holds.
- Retries with a delay, and `DEAD` jobs once `maxAttempts` is used up, crashes included.
- A lease reaper that returns jobs from crashed workers to the queue.
- Integration tests against real PostgreSQL, including a 10-worker, 1,000-job concurrency test.

[0.2.0]: https://github.com/sujal-taneja/kawa/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/sujal-taneja/kawa/releases/tag/v0.1.0
