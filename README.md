# Kawa

**Kawa** (川, "river") is a durable job queue service built on PostgreSQL.

Applications hand Kawa slow or unreliable work as *jobs*. Separate worker processes claim jobs over HTTP with a time-limited lease, run them, and acknowledge the result. If a worker crashes, its lease expires and the job is retried automatically. If a job keeps failing, it is parked as dead for inspection.

> **Status:** early development. The API is not stable yet.

## Guarantees

- **No lost jobs:** a job is acknowledged to the producer only after it is committed to PostgreSQL.
- **One holder at a time:** at most one worker holds a valid lease on a job.
- **No stuck jobs:** every lease expires; expired jobs return to the queue.
- **Bounded retries:** a job runs at most `maxAttempts` times, then becomes dead.
- **At-least-once delivery:** a job may run more than once (for example, if a worker crashes after finishing but before acknowledging), so job handlers should be idempotent.

## Planned API (v0.1)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/queues/{queue}/jobs` | Enqueue a job |
| `POST` | `/api/v1/queues/{queue}/take` | Claim the next ready job with a lease |
| `POST` | `/api/v1/jobs/{id}/ack` | Mark a claimed job as succeeded |
| `POST` | `/api/v1/jobs/{id}/nack` | Report a failure; the job is retried or marked dead |
| `GET` | `/api/v1/jobs/{id}` | Get a job's status |

## Roadmap

- **v0.1:** enqueue, lease-based claiming, ack/nack with retries, lease expiry, dead jobs
- **v0.2:** idempotency keys, delayed jobs, priorities, exponential backoff, heartbeats, long-polling
- **v0.3:** multi-tenancy with per-tenant concurrency and rate limits
- **v0.4:** Java worker SDK, metrics, admin API, `kawactl` CLI
- **v0.5:** transactional outbox events, signed webhooks, recurring jobs
- **v0.6:** reference deployment on AWS with Terraform
- **v1.0:** web dashboard, benchmarks

## Tech stack

Java 21 · Spring Boot · PostgreSQL · Maven · Testcontainers

## License

Licensed under the [Apache License 2.0](LICENSE).
