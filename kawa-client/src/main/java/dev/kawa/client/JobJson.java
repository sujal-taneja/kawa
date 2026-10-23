package dev.kawa.client;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The job exactly as the server sends it. Internal: callers get a Job or a LeasedJob instead.
 */
record JobJson(
        UUID id,
        String queue,
        String idempotencyKey,
        JsonNode payload,
        JobStatus status,
        int attempts,
        int maxAttempts,
        int priority,
        Instant availableAt,
        UUID leaseToken,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    Job toJob() {
        return new Job(id, queue, idempotencyKey, status, attempts, maxAttempts, priority, availableAt,
                createdAt, updatedAt, payload.toString());
    }

    LeasedJob toLeasedJob() {
        return new LeasedJob(id, queue, attempts, maxAttempts, priority, leaseExpiresAt, payload.toString(), leaseToken);
    }
}
