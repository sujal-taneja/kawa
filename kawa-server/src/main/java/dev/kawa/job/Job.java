package dev.kawa.job;

import java.time.Instant;
import java.util.UUID;

public record Job(
        UUID id,
        String queue,
        String idempotencyKey,
        String payload,
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
}
