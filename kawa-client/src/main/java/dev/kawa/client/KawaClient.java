package dev.kawa.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Talks to a Kawa server over its HTTP API. Create one per application and share it: it is immutable
 * and thread-safe, and reusing it reuses its open connections.
 *
 * <pre>{@code
 * KawaClient kawa = KawaClient.create("http://localhost:8080");
 * UUID id = kawa.enqueue("emails", new WelcomeEmail("ada@example.com"));
 * }</pre>
 */
public final class KawaClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI apiBase;
    private final HttpClient http;

    private KawaClient(URI serverUrl, HttpClient http) {
        String url = serverUrl.toString();
        this.apiBase = URI.create((url.endsWith("/") ? url : url + "/") + "api/v1/");
        this.http = http;
    }

    public static KawaClient create(String serverUrl) {
        Objects.requireNonNull(serverUrl, "serverUrl");
        return new KawaClient(URI.create(serverUrl),
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    /** Enqueues a job that can run right away, with the server's default settings. */
    public UUID enqueue(String queue, Object payload) {
        return enqueue(queue, payload, EnqueueOptions.none());
    }

    /**
     * Enqueues a job. The payload is any object Jackson can turn into JSON (a record, a Map, ...).
     * With an idempotency key, repeating the call returns the original job's id instead of a new job.
     */
    public UUID enqueue(String queue, Object payload, EnqueueOptions options) {
        Objects.requireNonNull(payload, "payload");
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.set("payload", Json.MAPPER.valueToTree(payload));
        if (options.priority() != null) {
            body.put("priority", options.priority());
        }
        if (options.maxAttempts() != null) {
            body.put("maxAttempts", options.maxAttempts());
        }
        if (options.delay() != null) {
            body.put("delaySeconds", options.delay().toSeconds());
        }
        if (options.runAt() != null) {
            body.put("runAt", options.runAt().toString());
        }
        HttpRequest.Builder request = post("queues/" + segment(queue) + "/jobs", body);
        if (options.idempotencyKey() != null) {
            request.header("Idempotency-Key", options.idempotencyKey());
        }
        JsonNode created = Json.MAPPER.readTree(send(request).body());
        return UUID.fromString(created.get("id").asString());
    }

    /** Looks up a job by id. Throws {@link JobNotFoundException} if there is none. */
    public Job get(UUID id) {
        HttpRequest.Builder request = HttpRequest.newBuilder(apiBase.resolve("jobs/" + id)).GET();
        return Json.read(send(request).body(), JobJson.class).toJob();
    }

    /**
     * Claims the next ready job in the queue (highest priority, then oldest) and leases it for the given
     * time. Empty when nothing is ready. Ack, nack or extend before the lease runs out.
     */
    public Optional<LeasedJob> take(String queue, Duration lease) {
        HttpResponse<String> response = send(post(
                "queues/" + segment(queue) + "/take?leaseSeconds=" + lease.toSeconds(), null));
        if (response.statusCode() == 204) {
            return Optional.empty();
        }
        return Optional.of(Json.read(response.body(), JobJson.class).toLeasedJob());
    }

    /** Marks the job done. Throws {@link LeaseLostException} if this worker no longer holds it. */
    public void ack(LeasedJob job) {
        send(post("jobs/" + job.id() + "/ack", leaseBody(job)));
    }

    /** Reports a failure: the job is retried after the server's default delay, or becomes DEAD if out of attempts. */
    public void nack(LeasedJob job) {
        send(post("jobs/" + job.id() + "/nack", leaseBody(job)));
    }

    /** Reports a failure and asks for the retry to wait at least {@code retryAfter} (whole seconds). */
    public void nack(LeasedJob job, Duration retryAfter) {
        ObjectNode body = leaseBody(job);
        body.put("seconds", retryAfter.toSeconds());
        send(post("jobs/" + job.id() + "/nack", body));
    }

    /** Keeps the lease for {@code lease} more from now, for jobs that need longer than first expected. */
    public void extend(LeasedJob job, Duration lease) {
        ObjectNode body = leaseBody(job);
        body.put("seconds", lease.toSeconds());
        send(post("jobs/" + job.id() + "/extend", body));
    }

    private static ObjectNode leaseBody(LeasedJob job) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("leaseToken", job.leaseToken().toString());
        return body;
    }

    private HttpRequest.Builder post(String path, JsonNode body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(apiBase.resolve(path));
        if (body == null) {
            return request.POST(HttpRequest.BodyPublishers.noBody());
        }
        return request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        HttpResponse<String> response;
        try {
            response = http.send(request.timeout(REQUEST_TIMEOUT).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new KawaConnectionException("Could not reach Kawa at " + apiBase + ": " + e, e);
        } catch (InterruptedException e) {
            // restore the flag so the caller's thread still knows it was asked to stop
            Thread.currentThread().interrupt();
            throw new KawaConnectionException("Interrupted while calling Kawa", e);
        }
        if (response.statusCode() >= 400) {
            throw toException(response);
        }
        return response;
    }

    private static KawaException toException(HttpResponse<String> response) {
        int status = response.statusCode();
        String detail = problemDetail(response.body()).orElse("Kawa answered HTTP " + status + ".");
        return switch (status) {
            case 404 -> new JobNotFoundException(detail);
            case 409 -> new LeaseLostException(detail);
            case 422 -> new IdempotencyKeyReusedException(detail);
            default -> new KawaRequestException(status, detail);
        };
    }

    // errors arrive as RFC 9457 problem details; fall back gracefully if a proxy answered with something else
    private static Optional<String> problemDetail(String body) {
        try {
            JsonNode detail = Json.MAPPER.readTree(body).get("detail");
            return detail == null || detail.isNull() ? Optional.empty() : Optional.of(detail.asString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String segment(String queue) {
        Objects.requireNonNull(queue, "queue");
        return URLEncoder.encode(queue, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
