<div align="center">

# Kawa

**A durable job queue built on PostgreSQL.**<br>
Hand slow work to Kawa, run it in workers written in any language, and never lose a job to a crash.

[![CI](https://github.com/sujal-taneja/kawa/actions/workflows/ci.yml/badge.svg)](https://github.com/sujal-taneja/kawa/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-ready-2496ED?logo=docker&logoColor=white)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

[Quickstart](#quickstart) · [Features](#features) · [Java client](#java-client) · [How it works](#how-it-works) · [Docs](#documentation)

</div>

---

**Kawa** (川, "river") takes slow or unreliable work off your request path.

- **Your app** (the *producer*) adds a job, such as "send this email" or "resize this image", with one HTTP call, and answers its user straight away.
- **Workers**, separate processes in any language, take jobs one at a time, do the work and report back.
- **Kawa** keeps every job safely in PostgreSQL in between. If a worker crashes mid-job, the job returns to the queue by itself. If a job keeps failing, it is set aside for inspection instead of retrying forever.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/architecture-dark.svg">
    <img src="docs/images/architecture-light.svg" alt="Producers enqueue jobs into Kawa; workers take, ack and nack jobs over HTTP; Kawa stores every job in a PostgreSQL table" width="100%">
  </picture>
</p>

## Quickstart

All you need is Docker.

```bash
git clone https://github.com/sujal-taneja/kawa.git && cd kawa
docker compose up -d        # Kawa + PostgreSQL; Kawa on http://localhost:8080
```

Open **http://localhost:8080/swagger-ui/index.html** to try every endpoint from your browser, or use `curl`:

```bash
# add a job
curl -X POST localhost:8080/api/v1/queues/emails/jobs \
  -H 'Content-Type: application/json' -d '{"payload": {"to": "ada@example.com"}}'

# take the next job: it comes back with a leaseToken
curl -X POST localhost:8080/api/v1/queues/emails/take

# report that it's done
curl -X POST localhost:8080/api/v1/jobs/<id>/ack \
  -H 'Content-Type: application/json' -d '{"leaseToken": "<leaseToken>"}'
```

If port 8080 is taken, start it with `KAWA_PORT=9090 docker compose up -d`.

## Features

**Reliability**
- **No job is handed out twice at once.** Workers claim jobs with `FOR UPDATE SKIP LOCKED`; a test releases 10 workers on 1,000 jobs and checks every job is claimed exactly once.
- **Crash recovery.** A claim is a lease with an expiry. If a worker dies, its job goes back on the queue.
- **Stale workers are fenced off.** Each lease has its own token, so a worker that froze past its lease can't finish a job someone else now holds.
- **Bounded retries.** A failed job is retried after a delay, up to `maxAttempts` (crashes count), then marked `DEAD` and kept for inspection.

**Scheduling**
- **Priorities** from 0 to 9, so urgent jobs jump the queue.
- **Delayed jobs** that run after a delay or at an exact time, up to a year ahead.
- **Idempotent enqueue.** Send an `Idempotency-Key` and retrying a request never creates a second job, even when the retries arrive together.

**Developer experience**
- **Plain HTTP and JSON**, documented with OpenAPI and Swagger UI, with errors in the RFC 9457 problem-details format.
- **A Java client** with a ready-made worker loop.
- **One command to run**, with Docker Compose.

## Java client

The client covers both halves, which usually live in two different programs.

**In your app, add jobs.** The payload is any object Jackson can turn into JSON; options are optional.

```java
KawaClient kawa = KawaClient.create("http://localhost:8080");

kawa.enqueue("thumbnails", new Thumbnail("beach.jpg", 200));

kawa.enqueue("thumbnails", new Thumbnail("profile-photo.jpg", 64), EnqueueOptions.builder()
        .priority(9)                          // jump the queue
        .delay(Duration.ofMinutes(5))         // or run later
        .idempotencyKey("avatar-ada")         // safe to retry
        .build());
```

**In a worker, do them.** Your code runs for every job: returning marks the job done, and throwing retries it later.

```java
KawaWorker worker = KawaWorker.builder(kawa, "thumbnails")
        .onJob(Thumbnail.class, t -> resize(t.image(), t.width()))
        .build();
worker.start();
Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));   // Ctrl+C finishes the current job first
```

Start as many workers as you like; they share the queue and never get the same job. The client needs Java 17+, no Spring, and has Jackson as its only dependency.

To see it all in action, [`examples/java-quickstart`](examples/java-quickstart) runs a producer and two workers against your local Kawa, covering priorities, delays, retries and idempotency. The [Java client guide](docs/java-client.md) covers installation, every option, and error handling.

## How it works

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/lifecycle-dark.svg">
    <img src="docs/images/lifecycle-light.svg" alt="Job lifecycle: enqueue creates a PENDING job; take moves it to LEASED; ack moves it to DONE; nack or lease expiry moves it back to PENDING while attempts remain, otherwise to DEAD" width="100%">
  </picture>
</p>

Every job is a row in one PostgreSQL table, and every change to a job is a single SQL statement:

- **`take`** locks the best ready job with `FOR UPDATE SKIP LOCKED`, so workers asking at the same moment each get a different job without waiting for one another. The job is leased under a fresh random token.
- **`ack` and `nack`** only succeed with the current token. A worker whose lease ran out gets `409 Conflict` instead of overwriting someone else's work.
- **A reaper** puts jobs from crashed workers back on the queue, and marks a job `DEAD` once it is out of attempts.

Delivery is **at-least-once**: a worker that finishes the work but crashes before reporting it causes a second run, so job handlers should be safe to repeat. The [architecture guide](docs/architecture.md) goes deeper.

### Crash recovery, live

Two workers share a queue. Worker A is killed with `kill -9` in the middle of a job; its lease runs out, the reaper returns the job, and worker B finishes it as attempt 2.

<p align="center">
  <img src="docs/images/demo.gif" alt="Two workers poll a queue; worker A is killed with kill -9 mid-job; the reaper returns its job to the queue and worker B finishes it as attempt 2" width="100%">
</p>

## Documentation

| Guide | What's inside |
|---|---|
| [HTTP API](docs/api.md) | Endpoints, enqueue options, priorities, delays, idempotency, errors |
| [Java client](docs/java-client.md) | `KawaClient`, `KawaWorker`, exceptions, writing safe handlers |
| [Architecture](docs/architecture.md) | The data model, claiming, leases and fencing, the reaper, guarantees |
| [Development](docs/development.md) | Building, testing, running from source, configuration |

## Tech stack

Java 21 · Spring Boot 4 · PostgreSQL 16 · Flyway · springdoc-openapi · Maven · JUnit 5 · Testcontainers · Docker · GitHub Actions

## License

[Apache License 2.0](LICENSE)
