# HTTP API

All endpoints live under `/api/v1` and speak JSON. With Kawa running, the interactive reference is at **http://localhost:8080/swagger-ui/index.html**, and the OpenAPI spec at `/v3/api-docs`.

| Method | Path | Body | Responses |
|---|---|---|---|
| `POST` | `/queues/{queue}/jobs` | `payload` (required), `maxAttempts`, `priority`, `runAt` or `delaySeconds`; optional `Idempotency-Key` header | `201` `{"id"}` · `200` idempotent replay · `400` · `422` |
| `POST` | `/queues/{queue}/take?leaseSeconds=30` | – | `200` the leased job · `204` nothing ready |
| `POST` | `/jobs/{id}/extend` | `{"leaseToken", "seconds"}` | `204` · `409` lease not held |
| `POST` | `/jobs/{id}/ack` | `{"leaseToken"}` | `204` · `409` |
| `POST` | `/jobs/{id}/nack` | `{"leaseToken", "seconds"}` | `204` retried after `seconds`, or DEAD · `409` |
| `GET` | `/jobs/{id}` | – | `200` the job · `404` |

A queue exists as soon as a job is enqueued to it.

## Enqueue options

| Field | Default | Meaning |
|---|---|---|
| `payload` | required | Any JSON value. Kawa stores it and hands it to the worker untouched. |
| `maxAttempts` | `5` | How many times the job may run, crashes included, before it becomes `DEAD`. |
| `priority` | `0` | `0` to `9`, where 9 is most urgent. |
| `delaySeconds` | – | Run the job after this many seconds (up to one year). |
| `runAt` | – | Run the job at this ISO-8601 instant (up to one year ahead). Use either `runAt` or `delaySeconds`. |

### Priorities

`take` hands out the highest-priority ready job first, and the oldest within the same priority, so a password-reset email doesn't wait behind 50,000 weekly digests. A job keeps its priority across retries, and a high-priority job scheduled for later doesn't block lower-priority jobs that are ready now.

Priorities are strict: under constant urgent traffic, low-priority jobs wait. Use separate queues when that matters.

### Delayed jobs

`"delaySeconds": 600` runs a job in 10 minutes; `"runAt": "2026-12-01T09:00:00Z"` runs it at that instant. A `runAt` in the past means now. A delayed job is a job whose `availableAt` is in the future; `take` skips it until it is due, so there is no timer to lose.

### Idempotent enqueue

Send an `Idempotency-Key` header (1 to 128 characters) to make an enqueue safe to retry.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="images/idempotency-dark.svg">
    <img src="images/idempotency-light.svg" alt="The same request with one Idempotency-Key is sent three times; the first creates the job, the retries create nothing and return the same job id" width="100%">
  </picture>
</p>

```bash
curl -X POST localhost:8080/api/v1/queues/emails/jobs \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1042-receipt' \
  -d '{"payload": {"to": "ada@example.com"}}'
```

- The first request creates the job: `201`.
- Repeating it with the same key and payload creates nothing and returns the same id: `200` with the header `Idempotent-Replayed: true`. This holds even when the retries arrive at the same moment.
- The same key with a different payload is rejected with `422`, because it almost always means a client bug.

Keys are scoped per queue. Payloads are compared as JSON, so key order and whitespace don't matter. Only the payload is compared, not the other options.

## Settling a job

`take` returns the job with a `leaseToken`. Every later call for that job must send it:

- `ack` marks the job `DONE`.
- `nack` reports a failure. The job is retried after `seconds` (default 10), or becomes `DEAD` when it has no attempts left.
- `extend` keeps the lease for `seconds` more (default 30), for jobs that need longer than expected.

All three answer `409` when the lease is no longer held: it expired and the job moved on, the job was already settled, or the token is wrong. See [leases and fencing tokens](architecture.md#leases-and-fencing-tokens).

## Errors

Errors use [RFC 9457 problem details](https://www.rfc-editor.org/rfc/rfc9457) with the content type `application/problem+json`:

```json
{
  "status": 409,
  "title": "Lease not held",
  "detail": "Job 816cc94a-… is not leased with this token. The lease may have expired and been reclaimed, the job may already be acked or nacked, or the token is wrong.",
  "instance": "/api/v1/jobs/816cc94a-…/ack",
  "jobId": "816cc94a-…"
}
```

| Status | Title | When |
|---|---|---|
| `400` | Invalid request | A field is out of range, or the body or a path value can't be parsed. |
| `404` | Job not found | No job has that id. |
| `409` | Lease not held | `ack`, `nack` or `extend` without the current lease. |
| `422` | Idempotency key reused | The key was already used with a different payload. |

## The job object

```json
{
  "id": "a122cf6c-0690-4442-8ca1-ad3a3adf2fe6",
  "queue": "emails",
  "idempotencyKey": null,
  "payload": { "to": "ada@example.com" },
  "status": "LEASED",
  "attempts": 1,
  "maxAttempts": 3,
  "priority": 0,
  "availableAt": "2026-10-23T06:38:19.474Z",
  "leaseToken": "ca6ef52b-d575-42da-8327-3d771c0052b9",
  "leaseExpiresAt": "2026-10-23T06:38:49.522Z",
  "createdAt": "2026-10-23T06:38:19.474Z",
  "updatedAt": "2026-10-23T06:38:19.522Z"
}
```

`status` is one of `PENDING`, `LEASED`, `DONE` or `DEAD`. `attempts` counts claims so far, including the current one.
