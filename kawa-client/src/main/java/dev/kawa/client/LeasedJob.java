package dev.kawa.client;

import java.time.Instant;
import java.util.UUID;

/**
 * A job this worker currently holds, as returned by {@link KawaClient#take}. Pass it back to
 * ack, nack or extend. The lease token stays inside, so it can't be lost or mixed up between jobs.
 */
public final class LeasedJob {

    private final UUID id;
    private final String queue;
    private final int attempts;
    private final int maxAttempts;
    private final int priority;
    private final Instant leaseExpiresAt;
    private final String payloadJson;
    private final UUID leaseToken;

    LeasedJob(UUID id, String queue, int attempts, int maxAttempts, int priority, Instant leaseExpiresAt,
              String payloadJson, UUID leaseToken) {
        this.id = id;
        this.queue = queue;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.priority = priority;
        this.leaseExpiresAt = leaseExpiresAt;
        this.payloadJson = payloadJson;
        this.leaseToken = leaseToken;
    }

    public UUID id() {
        return id;
    }

    public String queue() {
        return queue;
    }

    /** Which attempt this is, starting at 1. */
    public int attempts() {
        return attempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** True when a failure now would make the job DEAD instead of retrying it. */
    public boolean isLastAttempt() {
        return attempts >= maxAttempts;
    }

    public int priority() {
        return priority;
    }

    /** Finish (or extend) before this, or the job may be handed to another worker. */
    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String payloadJson() {
        return payloadJson;
    }

    /** The payload converted to your own type, e.g. {@code job.payload(WelcomeEmail.class)}. */
    public <T> T payload(Class<T> type) {
        return Json.read(payloadJson, type);
    }

    UUID leaseToken() {
        return leaseToken;
    }

    @Override
    public String toString() {
        return "LeasedJob[id=" + id + ", queue=" + queue + ", attempt=" + attempts + "/" + maxAttempts + "]";
    }
}
