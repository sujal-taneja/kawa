package dev.kawa.job;

import java.time.Instant;

/**
 * runAt and delaySeconds are alternatives; with neither, the job is runnable immediately.
 */
public record NewJob(
        String queue,
        String payloadJson,
        int maxAttempts,
        String idempotencyKey,
        Instant runAt,
        int delaySeconds
) {

    public static NewJob now(String queue, String payloadJson, int maxAttempts) {
        return new NewJob(queue, payloadJson, maxAttempts, null, null, 0);
    }
}
