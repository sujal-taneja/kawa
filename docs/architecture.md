# Architecture

Kawa is a single Spring Boot service in front of PostgreSQL. PostgreSQL is both the store and the queue: every job is a row in one `jobs` table, and every state change is a single SQL statement.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="images/architecture-dark.svg">
    <img src="images/architecture-light.svg" alt="Producers enqueue jobs into Kawa; workers take, ack and nack jobs over HTTP; Kawa stores every job in a PostgreSQL table" width="100%">
  </picture>
</p>

- **Producers** are your services. They enqueue jobs with one HTTP call and move on.
- **Workers** are separate processes, in any language, that claim jobs, run them and report back.
- **Kawa** keeps every job in PostgreSQL, hands each one to a single worker at a time, and recovers jobs from workers that disappear.

## Job lifecycle

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="images/lifecycle-dark.svg">
    <img src="images/lifecycle-light.svg" alt="enqueue creates a PENDING job; take moves it to LEASED; ack moves it to DONE; nack or lease expiry moves it back to PENDING while attempts remain, otherwise to DEAD" width="100%">
  </picture>
</p>

| Status | Meaning |
|---|---|
| `PENDING` | Waiting. Runnable once `available_at` has passed. |
| `LEASED` | Held by exactly one worker until `lease_expires_at`. |
| `DONE` | Acked. Terminal. |
| `DEAD` | Out of attempts. Terminal, and kept for inspection. |

## The jobs table

| Column | Purpose |
|---|---|
| `id` | Random UUID: unguessable, and needs no shared counter. |
| `queue`, `payload` (`jsonb`) | Where the job belongs, and its data, which Kawa never interprets. |
| `status`, `attempts`, `max_attempts` | The state machine and the retry budget. |
| `priority` | 0 to 9, enforced by a `CHECK` constraint. |
| `available_at` | "Not before": used by delayed jobs and retry delays. |
| `lease_token`, `lease_expires_at` | Set only while `LEASED`. |
| `idempotency_key`, `payload_hash` | Deduplicate retried enqueues. |

Two partial indexes serve the two hot queries:

- `(queue, priority DESC, available_at) WHERE status = 'PENDING'` for claiming.
- `(lease_expires_at) WHERE status = 'LEASED'` for the reaper.

Finished jobs are in neither index, so the indexes stay small however many jobs have completed. Measured with `EXPLAIN ANALYZE` on a table of one million jobs, 90% of them scheduled in the future, a claim reads a single index entry and takes about 0.05 ms, against about 49 ms for a full table scan.

## Claiming without collisions

`take` is one statement:

```sql
UPDATE jobs
SET status = 'LEASED', lease_token = :newToken, lease_expires_at = now() + :lease,
    attempts = attempts + 1, updated_at = now()
WHERE id = (
    SELECT id FROM jobs
    WHERE queue = :queue AND status = 'PENDING' AND available_at <= now()
    ORDER BY priority DESC, available_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
RETURNING *
```

- **One statement means one transaction.** No other worker can slip in between finding a job and claiming it.
- **`FOR UPDATE`** locks the chosen row until the transaction ends.
- **`SKIP LOCKED`** makes a second worker pass over locked rows and take the next one, instead of waiting in line. Many workers claim different jobs at the same moment, with no external lock service.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="images/claiming-dark.svg">
    <img src="images/claiming-light.svg" alt="Two workers call take at the same moment: worker A locks job 7, worker B finds job 7 locked, skips it and locks job 8" width="100%">
  </picture>
</p>

`attempts` grows on every claim, not on every failure. A crash therefore uses up an attempt too, which is what eventually stops a job that crashes every worker.

## Leases and fencing tokens

Every claim writes a new random `lease_token` and an expiry. `ack`, `nack` and `extend` only change the row if they present the current token:

```sql
UPDATE jobs SET status = 'DONE', lease_token = NULL, lease_expires_at = NULL, updated_at = now()
WHERE id = :id AND lease_token = :token AND status = 'LEASED'
```

Zero rows updated becomes `409 Conflict`. This protects against a stalled worker: if worker A freezes past its lease and the job is handed to worker B with a new token, A's late ack matches nothing and B stays in charge.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="images/fencing-dark.svg">
    <img src="images/fencing-light.svg" alt="Worker A takes job 7 with token aaa and freezes; the lease expires and the reaper returns the job; worker B takes it with token bbb; worker A's late ack with token aaa is rejected with 409, and worker B's ack marks the job done" width="100%">
  </picture>
</p>

Expiry is passive. Nothing happens at `lease_expires_at` itself; the job is only returned by the reaper. Until the reaper runs, the original holder can still ack, which is safe because no one else holds the job yet.

## The reaper

A scheduled task runs every few seconds (`kawa.reaper.interval-ms`, default 5000):

```sql
UPDATE jobs
SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
    lease_token = NULL, lease_expires_at = NULL, updated_at = now()
WHERE status = 'LEASED' AND lease_expires_at < now()
```

- **It returns jobs from workers that died** without acking or nacking.
- **It marks jobs `DEAD` when their last attempt expired**, so a poison job can't crash workers forever.
- **It is safe on every instance at once.** Row locks plus the `status = 'LEASED'` re-check mean each expired job is reaped exactly once, so running several Kawa instances needs no leader election.

A crashed worker's job is back in the queue at most one lease plus one reaper interval after the crash.

## Delivery guarantees

| Guarantee | What it means |
|---|---|
| **No lost jobs** | A job is acknowledged to the producer only after it is committed to PostgreSQL. |
| **One holder at a time** | At most one worker holds a valid lease on a job. |
| **No stuck jobs** | Every lease expires, and expired jobs return to the queue. |
| **Bounded retries** | A job runs at most `maxAttempts` times, crashes included, then becomes `DEAD`. |
| **Exactly-once enqueue** | With an `Idempotency-Key`, retried enqueues never create a second job. |
| **At-least-once delivery** | A job can run more than once: a worker may finish the work and crash before acking. |

Exactly-once *processing* across separate systems isn't possible: Kawa can't tell "crashed before sending the email" from "crashed after sending it". Make handlers idempotent instead, for example by using the job id as a deduplication key downstream. At-least-once delivery plus an idempotent handler gives effectively-once processing.

## Clocks

Every time comparison uses the database clock (`now()`): lease expiry, delays, the reaper. Application servers whose clocks drift apart can't release jobs early or late.
