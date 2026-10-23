# Java client

[`kawa-client`](../kawa-client) wraps the HTTP API in a small library: Java 17+, no Spring, and Jackson as its only dependency.

## Installing

The client isn't published yet. Install it into your local Maven repository from a clone of this repository:

```bash
./mvnw install -pl kawa-client -am
```

`-am` also installs the parent pom, which the client's pom refers to.

Then depend on it:

```xml
<dependency>
  <groupId>dev.kawa</groupId>
  <artifactId>kawa-client</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

## The client

```java
KawaClient kawa = KawaClient.create("http://localhost:8080");
```

Create one client per application and share it. It is immutable and thread-safe, and it reuses its HTTP connections.

### Enqueueing

The payload is any object Jackson can turn into JSON, such as a record or a `Map`:

```java
UUID id = kawa.enqueue("emails", new WelcomeEmail("ada@example.com"));

kawa.enqueue("emails", new PasswordReset("ada"), EnqueueOptions.builder()
        .priority(9)                        // 0-9, 9 = most urgent
        .delay(Duration.ofMinutes(10))      // or .runAt(Instant)
        .maxAttempts(3)
        .idempotencyKey("reset-ada-42")     // safe to retry: never creates a second job
        .build());

Job job = kawa.get(id);                     // status, attempts, priority, availableAt, payload(...)
```

Options that aren't set use the server's defaults. The server validates them and is the single source of truth.

## Workers

`KawaWorker` runs your code for every job in a queue:

```java
KawaWorker worker = KawaWorker.builder(kawa, "emails")
        .onJob(WelcomeEmail.class, email -> mailer.send(email))
        .build();
worker.start();
Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));
```

- **Return normally** and the job is acked. **Throw** and it is nacked: retried later, or `DEAD` on its last attempt.
- **`onJob(job -> ...)`** gives you the whole `LeasedJob` instead: `id()`, `attempts()`, `isLastAttempt()`, `payload(Type.class)`.
- **One job at a time**, on a background thread named `kawa-worker-<queue>`.
- **It waits `pollInterval` (default 1 s)** when the queue is empty, and `errorBackoff` (default 5 s) when Kawa can't be reached. A lost lease is logged and skipped. None of these stop the worker.
- **`stop()` is graceful.** It never interrupts a running handler: the current job finishes and is settled, then the worker exits. An idle worker stops immediately.
- **Leases aren't renewed automatically.** Set `lease(...)` longer than your slowest job (default 30 s), or call `kawa.extend(job, duration)` from your handler.

Logging goes through `System.Logger`, so it follows whatever logging framework the application uses.

### Handlers should be idempotent

Delivery is at-least-once: if a worker crashes after your handler ran but before the ack, the job runs again. Use the job id as a deduplication key where the work happens:

```java
.onJob(job -> payments.charge(job.payload(Charge.class), job.id().toString()))   // the API's idempotency key
```

## Low-level use

For full control, drive the cycle yourself:

```java
kawa.take("emails", Duration.ofSeconds(30)).ifPresent(job -> {
    try {
        send(job.payload(WelcomeEmail.class));
        kawa.ack(job);
    } catch (LeaseLostException e) {
        // the lease expired and another worker has the job now: drop it
    } catch (Exception e) {
        kawa.nack(job, Duration.ofSeconds(10));
    }
});
```

The lease token stays inside the `LeasedJob`, so it can't be lost or mixed up between jobs.

## Errors

All errors are unchecked and carry the server's explanation:

| Exception | When |
|---|---|
| `LeaseLostException` | `409`: the job is no longer leased with this token. |
| `JobNotFoundException` | `404`: no job with that id. |
| `IdempotencyKeyReusedException` | `422`: the key was used with a different payload. |
| `KawaRequestException` | Any other error response; `status()` holds the HTTP status. |
| `KawaConnectionException` | Kawa couldn't be reached. Retrying an enqueue is only safe with an idempotency key. |

`KawaException` is the common base class.
