package dev.kawa.client;

import java.time.Instant;
import java.util.UUID;

/**
 * A snapshot of a job, as returned by {@link KawaClient#get(UUID)}.
 */
public record Job(
        UUID id,
        String queue,
        String idempotencyKey,
        JobStatus status,
        int attempts,
        int maxAttempts,
        int priority,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt,
        String payloadJson
) {

    /**
     * The payload converted to your own type, e.g. {@code job.payload(WelcomeEmail.class)}.
     */
    public <T> T payload(Class<T> type) {
        return Json.read(payloadJson, type);
    }
}
