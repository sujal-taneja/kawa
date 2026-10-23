package dev.kawa.job;

import java.util.UUID;

/**
 * created is false when an Idempotency-Key matched an existing job; payloadMatches says whether
 * that job was enqueued with the same payload.
 */
public record EnqueueResult(UUID jobId, boolean created, boolean payloadMatches) {
}
