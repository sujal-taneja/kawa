# Java quickstart

A producer and a worker built with the [Kawa Java client](../../docs/java-client.md). The jobs are pretend image thumbnails: the worker "resizes" each one by waiting two seconds.

| File | Role |
|---|---|
| [`Producer.java`](src/main/java/dev/kawa/examples/Producer.java) | Your app's side: adds jobs, including an urgent one, a delayed one, one that always fails, and a retried request with an idempotency key. |
| [`Worker.java`](src/main/java/dev/kawa/examples/Worker.java) | The worker's side: a `KawaWorker` that runs for every job until stopped. |
| [`Thumbnail.java`](src/main/java/dev/kawa/examples/Thumbnail.java) | The job's payload: a plain record. |

## Running it

Needs Docker and Java 17+. From the repository root:

```bash
docker compose up -d                                     # Kawa on localhost:8080
./mvnw -q install -pl kawa-client -am -DskipTests        # once: installs the client locally
```

Then start two workers and the producer, each in its own terminal:

```bash
./mvnw -q -pl examples/java-quickstart exec:java@worker -Dexec.args=worker-A
./mvnw -q -pl examples/java-quickstart exec:java@worker -Dexec.args=worker-B
./mvnw -q -pl examples/java-quickstart exec:java@producer
```

Set `KAWA_URL` if Kawa isn't on `http://localhost:8080`.

## What you'll see

```
[worker-A] resizing profile-photo.jpg to 64px  (priority 9, attempt 1/5)   <- enqueued 5th, taken 1st
[worker-B] resizing beach.jpg to 200px  (priority 0, attempt 1/5)          <- the two workers share the queue
[worker-A] done     profile-photo.jpg
...
[worker-B] resizing corrupted.jpg to 200px  (priority 0, attempt 1/2)
[kawa] Handler failed for LeasedJob[... attempt=1/2]; it will be retried
[worker-A] resizing banner.jpg to 800px  (priority 0, attempt 1/5)         <- the delayed job, 15 s later
[worker-B] resizing corrupted.jpg to 200px  (priority 0, attempt 2/2)
[kawa] Handler failed for LeasedJob[... attempt=2/2]; no attempts left, the job will be DEAD
```

- **Priority**: the urgent job jumps the queue.
- **Sharing**: the two workers never get the same job.
- **Delays**: the delayed job waits for its time.
- **Retries**: the broken job is retried once, then marked `DEAD`.
- **Idempotency**: the retried request creates only one job.

Press Ctrl+C in a worker's terminal: it finishes the job it's working on, then stops.
